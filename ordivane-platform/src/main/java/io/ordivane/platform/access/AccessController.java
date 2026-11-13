package io.ordivane.platform.access;

import java.util.UUID;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅返回会话身份和 CSRF，密码及来源凭据从不进入响应。 */
@RestController
@RequestMapping("/api/v1/access")
public class AccessController {
    /** 非秘密身份，角色为 OBSERVER、OPERATOR、APPROVER 三者之一。 */
    public record Identity(UUID id, String username, AccessRole role) { }

    /** token 为当前会话的掩码 CSRF 值；客户端按 headerName 提交。 */
    public record Csrf(String headerName, String token) { }

    private final AccessPolicy policy;

    public AccessController(AccessPolicy policy) { this.policy = policy; }

    @GetMapping("/me")
    Identity me() {
        HumanPrincipal human = policy.requireHuman();
        return new Identity(human.id(), human.username(), human.role());
    }

    @GetMapping("/csrf")
    Csrf csrf(CsrfToken token) { return new Csrf(token.getHeaderName(), token.getToken()); }
}
