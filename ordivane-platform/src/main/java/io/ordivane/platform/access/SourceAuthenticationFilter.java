package io.ordivane.platform.access;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.ordivane.platform.audit.AuditService.Result;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅用于来源链；不接受会话，不创建会话，不注册成全局 Servlet Filter。 */
final class SourceAuthenticationFilter extends OncePerRequestFilter {
    private final AccessRepository repository;
    private final SecurityBoundary boundary;

    SourceAuthenticationFilter(AccessRepository repository, SecurityBoundary boundary) {
        this.repository = repository;
        this.boundary = boundary;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() > 519
                || header.length() < 39) {
            boundary.unauthenticated(request, response);
            return;
        }
        var source = repository.findSource(header.substring(7));
        if (source.isEmpty()) {
            boundary.unauthenticated(request, response);
            return;
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new SourcePrincipal(source.get()), null, List.of(new SimpleGrantedAuthority("SOURCE_REPORT")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        boundary.record(request, authentication, "SOURCE_AUTHENTICATE", Result.SUCCESS);
        chain.doFilter(request, response);
    }
}
