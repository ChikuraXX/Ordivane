package io.ordivane.platform.access;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.ordivane.platform.audit.AuditService;
import io.ordivane.platform.audit.AuditService.Result;
import io.ordivane.platform.audit.AuditService.Subject;
import io.ordivane.platform.audit.AuditService.SubjectType;
import io.ordivane.platform.shared.ApiError;
import io.ordivane.platform.shared.TraceFilter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 安全拒绝和登录/退出的唯一审计与外部错误翻译边界。 */
@Component
public class SecurityBoundary {
    private final AuditService audit;
    private final ObjectMapper mapper;

    public SecurityBoundary(AuditService audit, ObjectMapper mapper) {
        this.audit = audit;
        this.mapper = mapper;
    }

    /** 主体仅取认证上下文，不记录失败登录提交的用户名或密码。 */
    public void record(HttpServletRequest request, Authentication authentication, String action, Result result) {
        Subject subject = new Subject(SubjectType.ANONYMOUS, "anonymous");
        if (authentication != null && authentication.isAuthenticated()) {
            if (authentication.getPrincipal() instanceof HumanPrincipal human) {
                subject = new Subject(SubjectType.HUMAN, human.id().toString());
            } else if (authentication.getPrincipal() instanceof SourcePrincipal source) {
                subject = new Subject(SubjectType.SOURCE, source.sourceId());
            }
        }
        boolean sourceRequest = request.getServletPath().equals("/api/v1/failure-reports")
                || request.getServletPath().startsWith("/api/v1/failure-reports/");
        audit.record(subject, action, sourceRequest ? "SOURCE_ENDPOINT" : "HUMAN_SESSION",
                sourceRequest ? "failure-reports" : "access", result, TraceFilter.traceId(request));
    }

    /** 401 不重定向登录页、不暴露用户是否存在。 */
    public void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        record(request, null, "AUTHENTICATE", Result.DENIED);
        write(request, response, 401, "UNAUTHENTICATED", "身份或凭据无效");
    }

    /** 403 区分 CSRF 与角色拒绝，避免客户端误当成登录失效。 */
    public void denied(HttpServletRequest request, HttpServletResponse response, Exception exception) throws IOException {
        record(request, SecurityContextHolder.getContext().getAuthentication(), "AUTHORIZE", Result.DENIED);
        write(request, response, 403, exception instanceof CsrfException ? "CSRF_REJECTED" : "FORBIDDEN",
                exception instanceof CsrfException ? "请求校验失败" : "无权执行此操作");
    }

    /** 输出安全错误，调用者不得传入异常内容或凭据。 */
    public void write(HttpServletRequest request, HttpServletResponse response, int status,
                      String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message, TraceFilter.traceId(request)));
    }
}
