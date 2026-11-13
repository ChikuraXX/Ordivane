package io.ordivane.platform.shared;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** 最外层请求边界；不信任传入 traceId，不记录请求凭据和参数。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(TraceFilter.class);
    private final ObjectMapper mapper;

    public TraceFilter(ObjectMapper mapper) { this.mapper = mapper; }

    /** 同一请求的错误、审计和响应头共用服务端 traceId。 */
    public static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceFilter.class.getName());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(TraceFilter.class.getName(), traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(request, response);
        } catch (Exception exception) {
            LOG.error("请求过滤边界失败 traceId={}", traceId, exception);
            if (response.isCommitted()) { throw new ServletException("响应已经提交", exception); }
            response.resetBuffer();
            response.setStatus(500);
            response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getOutputStream(), new ApiError("INTERNAL_ERROR", "服务暂时不可用", traceId));
        }
    }
}
