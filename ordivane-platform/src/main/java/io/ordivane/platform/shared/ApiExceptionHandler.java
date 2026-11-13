package io.ordivane.platform.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import io.ordivane.platform.access.SecurityBoundary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;

/** MVC 最终错误翻译边界，安全过滤器另由统一安全边界负责。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final SecurityBoundary security;

    public ApiExceptionHandler(SecurityBoundary security) { this.security = security; }

    /** 接入与案件业务错误沿用统一trace，避免将请求或依赖异常暴露给客户端。 */
    @ExceptionHandler(io.ordivane.platform.ingestion.IngestionException.class)
    ResponseEntity<ApiError> business(io.ordivane.platform.ingestion.IngestionException exception, HttpServletRequest request) {
        return error(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    void forbidden(AccessDeniedException exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        security.denied(request, response, exception);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> invalid(Exception exception, HttpServletRequest request) {
        return error(400, "INVALID_REQUEST", "请求格式无效", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(NoResourceFoundException exception, HttpServletRequest request) {
        return error(404, "NOT_FOUND", "资源不存在", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            return error(response.getStatusCode().value(), "INVALID_REQUEST", "请求格式或方法无效", request);
        }
        LOG.error("请求处理失败 traceId={}", TraceFilter.traceId(request), exception);
        return error(500, "INTERNAL_ERROR", "服务暂时不可用", request);
    }

    private ResponseEntity<ApiError> error(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(code, message, TraceFilter.traceId(request)));
    }
}
