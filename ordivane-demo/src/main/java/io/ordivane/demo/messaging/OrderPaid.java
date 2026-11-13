package io.ordivane.demo.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;
import io.ordivane.demo.order.BusinessException;

/** OrderPaid v1不可变业务信封；恢复元数据只通过消息头传递。 */
public record OrderPaid(UUID eventId, String source, String eventType, Integer schemaVersion,
                        OffsetDateTime occurredAt, Payload payload) {
    /** 会员开通目标，业务键为orderId和entitlementType。 */
    public record Payload(String orderId, String entitlementType) { }

    /** 严格校验版本、来源及固定权益，不接受空值或隐式默认。 */
    public void validate() {
        if (eventId == null || !"ordivane-demo".equals(source) || !"OrderPaid".equals(eventType)
                || !Integer.valueOf(1).equals(schemaVersion) || occurredAt == null || payload == null) {
            throw new BusinessException("INVALID_EVENT", 400, "事件信封或版本无效");
        }
        validateTarget(payload.orderId(), payload.entitlementType());
    }

    /** 限制订单标识为可路由安全字符；当前仅支持会员权益。 */
    public static void validateTarget(String orderId, String entitlementType) {
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,100}")
                || !"MEMBERSHIP".equals(entitlementType)) {
            throw new BusinessException("INVALID_TARGET", 400, "订单标识或权益类型无效");
        }
    }
}
