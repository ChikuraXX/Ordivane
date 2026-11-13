package io.ordivane.demo.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 演示API的独立服务凭据边界；凭据缺失时关闭业务访问。 */
@Configuration
public class DemoApiConfiguration implements WebMvcConfigurer {
    private final String token;
    /** 从现有进程环境注入，不使用硬编码账号。 */
    public DemoApiConfiguration(@Value("${ORDIVANE_DEMO_API_TOKEN:}") String token) { this.token = token; }
    /** 只保护业务API，不改变既有健康检查。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String supplied = request.getHeader("Authorization");
                if (token.isBlank() || supplied == null || !MessageDigest.isEqual(
                        ("Bearer " + token).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                    throw new BusinessException("UNAUTHORIZED", 401, "演示服务凭据无效");
                }
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }

    /** 统一HTTP错误结构，不暴露内部异常或凭据。 */
    @RestControllerAdvice
    public static class ApiErrors {
        /** 返回已知业务拒绝。 */
        @ExceptionHandler(BusinessException.class)
        public ResponseEntity<Map<String, String>> business(BusinessException error) {
            return ResponseEntity.status(error.status()).body(body(error.code(), error.getMessage()));
        }
        /** 返回框架输入错误，避免泄露原请求值。 */
        @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
                org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
                org.springframework.web.bind.MissingServletRequestParameterException.class})
        public ResponseEntity<Map<String, String>> invalid(Exception error) {
            return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "请求格式无效"));
        }
        /** 数据库等非预期失败由此边界一次记录并返回失败。 */
        @ExceptionHandler(Exception.class)
        public ResponseEntity<Map<String, String>> unexpected(Exception error) {
            var payload = body("INTERNAL_ERROR", "操作未完成，请查询业务结果后重试");
            org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("演示API失败 traceId={}", payload.get("traceId"), error);
            return ResponseEntity.internalServerError().body(payload);
        }
        /** 为每个失败生成独立诊断标识。 */
        private static Map<String, String> body(String code, String message) {
            return Map.of("code", code, "message", message, "traceId", UUID.randomUUID().toString());
        }
    }
}
