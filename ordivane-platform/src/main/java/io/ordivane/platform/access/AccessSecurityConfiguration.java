package io.ordivane.platform.access;

import io.ordivane.platform.audit.AuditService.Result;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/** 来源请求和人类会话使用独立安全链；业务权限仍由执行入口检查。 */
@Configuration
@EnableMethodSecurity
public class AccessSecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService userDetailsService(AccessRepository repository) { return repository::loadHuman; }

    /** 来源仅接入失败报告资源；BE-030 实现业务控制器并核对 source 声明。 */
    @Bean
    @Order(1)
    SecurityFilterChain sourceSecurity(HttpSecurity http, AccessRepository repository,
                                       SecurityBoundary boundary) throws Exception {
        http.securityMatcher("/api/v1/failure-reports", "/api/v1/failure-reports/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.requireExplicitSave(true))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SOURCE_REPORT"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> boundary.unauthenticated(req, res))
                        .accessDeniedHandler(boundary::denied))
                .addFilterAfter(new SourceAuthenticationFilter(repository, boundary), SecurityContextHolderFilter.class);
        return http.build();
    }

    /** 登录同样要求 CSRF，登录后需重新取 token；不启用 Basic、Remember-Me 或跨域凭据。 */
    @Bean
    @Order(2)
    SecurityFilterChain humanSecurity(HttpSecurity http, AccessRepository repository,
                                      UserDetailsService users, PasswordEncoder encoder,
                                      SecurityBoundary boundary) throws Exception {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        http.authenticationProvider(provider)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/access/csrf", "/api/v1/access/login", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(login -> login.loginPage("/api/v1/access/login").loginProcessingUrl("/api/v1/access/login")
                        .successHandler((req, res, authentication) -> {
                            boundary.record(req, authentication, "LOGIN", Result.SUCCESS);
                            res.setStatus(204);
                        })
                        .failureHandler((req, res, exception) -> {
                            boundary.record(req, null, "LOGIN", Result.DENIED);
                            boundary.write(req, res, 401, "UNAUTHENTICATED", "账号或凭据无效");
                        }))
                .logout(logout -> logout.logoutUrl("/api/v1/access/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((req, res, authentication) -> {
                            boundary.record(req, authentication, "LOGOUT", Result.SUCCESS);
                            res.setStatus(204);
                        }))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> boundary.unauthenticated(req, res))
                        .accessDeniedHandler(boundary::denied))
                .addFilterAfter(new HumanSessionFilter(repository, boundary), SecurityContextHolderFilter.class)
                .headers(headers -> headers.cacheControl(Customizer.withDefaults()));
        return http.build();
    }
}
