package io.ordivane.demo.membership;

import java.util.Map;
import java.util.UUID;
import io.ordivane.demo.messaging.OrderPaid;
import io.ordivane.demo.order.BusinessException;
import io.ordivane.demo.order.OrderService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原始与恢复共用的原子会员操作，数据库唯一键保证一次业务效果。 */
@Service
public class MembershipService {
    private final JdbcTemplate jdbc;
    private final OrderService orders;

    /** 注入相同演示数据源及订单锁服务。 */
    public MembershipService(JdbcTemplate jdbc, OrderService orders) { this.jdbc = jdbc; this.orders = orders; }

    /** 原始executionId为空；恢复为非空UUID。权益与处理结果同事务提交。 */
    @Transactional
    public String apply(OrderPaid event, UUID executionId) {
        event.validate();
        var target = event.payload();
        Map<String, Object> order = orders.lock(target.orderId());
        if (!target.entitlementType().equals(order.get("entitlement_type"))) {
            throw new BusinessException("TARGET_MISMATCH", 409, "订单权益不匹配");
        }
        // 以本地已提交的支付事实核对完整事件，不能凭外来信封直接开通会员。
        var originals = jdbc.queryForList("SELECT body FROM outbox_event WHERE event_id=? AND order_id=?", event.eventId(), target.orderId());
        if (originals.isEmpty() || !event.equals(toEvent((String) originals.getFirst().get("body")))) {
            throw new BusinessException("EVENT_MISMATCH", 409, "事件与支付事实不匹配");
        }
        String result;
        if ("REFUNDED".equals(order.get("status"))) {
            result = "REFUND_REJECTED";
        } else if (!"PAID".equals(order.get("status"))) {
            throw new BusinessException("ORDER_NOT_PAID", 409, "订单尚未支付");
        } else {
            int inserted = jdbc.update("INSERT INTO membership_entitlement(order_id,entitlement_type,event_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    target.orderId(), target.entitlementType(), event.eventId());
            result = inserted == 1 ? "GRANTED" : "ALREADY_GRANTED";
        }
        jdbc.update("INSERT INTO processing_record(attempt_id,event_id,execution_id,order_id,entitlement_type,origin,result) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), event.eventId(), executionId, target.orderId(), target.entitlementType(),
                executionId == null ? "ORIGINAL" : "RECOVERY", result);
        return result;
    }

    /** 解码由本服务写入的不可变支付事件。 */
    private OrderPaid toEvent(String body) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readValue(body, OrderPaid.class);
    }
}
