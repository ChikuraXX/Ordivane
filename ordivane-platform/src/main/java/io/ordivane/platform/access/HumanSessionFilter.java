package io.ordivane.platform.access;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 人类链禁止服务凭据，并在请求时重读账号，避免禁用或降权后的旧会话保留权限。 */
final class HumanSessionFilter extends OncePerRequestFilter {
    private final AccessRepository repository;
    private final SecurityBoundary boundary;

    HumanSessionFilter(AccessRepository repository, SecurityBoundary boundary) {
        this.repository = repository;
        this.boundary = boundary;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getHeader("Authorization") != null) {
            boundary.unauthenticated(request, response);
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof HumanPrincipal human) {
            try {
                HumanPrincipal current = repository.loadHuman(human.username());
                if (!current.enabled() || !current.id().equals(human.id()) || current.role() != human.role()
                        || !current.password().equals(human.password())) {
                    invalidate(request, response);
                    return;
                }
            } catch (UsernameNotFoundException exception) {
                invalidate(request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void invalidate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession(false);
        if (session != null) { session.invalidate(); }
        SecurityContextHolder.clearContext();
        boundary.unauthenticated(request, response);
    }
}
