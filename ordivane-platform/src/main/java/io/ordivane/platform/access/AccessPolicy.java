package io.ordivane.platform.access;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 后续业务在执行边界调用；不从请求字段接收当前用户或角色。 */
@Component
public class AccessPolicy {
    /** 获取当前人类身份，排除来源、匿名及已禁用账号。 */
    public HumanPrincipal requireHuman() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof HumanPrincipal human) || !human.enabled()) {
            throw new AccessDeniedException("需要人类身份");
        }
        return human;
    }

    /** 只有操作员可执行操作，审批人和观察者不隐式继承此权限。 */
    public HumanPrincipal requireOperator() {
        HumanPrincipal human = requireHuman();
        if (human.role() != AccessRole.OPERATOR) { throw new AccessDeniedException("需要操作员权限"); }
        return human;
    }

    /** 原申请人 ID 必须来自持久化计划，禁止请求方传入冒充另一申请人。 */
    public HumanPrincipal requireOtherApprover(UUID persistedRequesterId) {
        HumanPrincipal human = requireHuman();
        if (persistedRequesterId == null || human.role() != AccessRole.APPROVER
                || human.id().equals(persistedRequesterId)) {
            throw new AccessDeniedException("需要其他审批人确认");
        }
        return human;
    }

    /** 校验上报来源声明；失败报告业务模块须在持久化前调用。 */
    public SourcePrincipal requireSource(String declaredSource) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SourcePrincipal source)
                || !source.sourceId().equals(declaredSource)) {
            throw new AccessDeniedException("来源身份不匹配");
        }
        return source;
    }
}
