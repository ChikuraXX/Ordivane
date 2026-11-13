package io.ordivane.demo.order;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.ordivane.demo.messaging.OrderPaid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 订单状态及支付事件的权威事务边界。 */
@Service
public class OrderService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    /** 使用演示库连接；不访问平台库。 */
    public OrderService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 创建模拟订单；同标识同权益幂等，状态不回退。 */
    @Transactional
    public Map<String, Object> create(String orderId, String entitlementType) {
        OrderPaid.validateTarget(orderId, entitlementType);
        jdbc.update("INSERT INTO orders(order_id,entitlement_type,status) VALUES (?,?,'CREATED') ON CONFLICT DO NOTHING",
                orderId, entitlementType);
        return find(orderId);
    }

    /** 同事务支付和写入唯一Outbox，重复支付返回原事件。 */
    @Transactional
    public Map<String, Object> pay(String orderId) {
        Map<String, Object> order = lock(orderId);
        if ("REFUNDED".equals(order.get("status"))) {
            throw new BusinessException("ORDER_REFUNDED", 409, "退款订单不可支付");
        }
        if ("CREATED".equals(order.get("status"))) {
            OrderPaid event = new OrderPaid(UUID.randomUUID(), "ordivane-demo", "OrderPaid", 1,
                    OffsetDateTime.now(), new OrderPaid.Payload(orderId, (String) order.get("entitlement_type")));
            jdbc.update("UPDATE orders SET status='PAID',version=version+1,updated_at=now() WHERE order_id=?", orderId);
            jdbc.update("INSERT INTO outbox_event(event_id,order_id,body) VALUES (?,?,?)",
                    event.eventId(), orderId, json.writeValueAsString(event));
        }
        return jdbc.queryForMap("SELECT event_id,order_id,created_at,published_at FROM outbox_event WHERE order_id=?", orderId);
    }

    /** 支付后退款；保留历史权益以供核验，不新增权益。 */
    @Transactional
    public Map<String, Object> refund(String orderId) {
        Map<String, Object> order = lock(orderId);
        if ("CREATED".equals(order.get("status"))) {
            throw new BusinessException("ORDER_NOT_PAID", 409, "未支付订单不可退款");
        }
        jdbc.update("UPDATE orders SET status='REFUNDED',version=version+1,updated_at=now() WHERE order_id=? AND status='PAID'", orderId);
        return find(orderId);
    }

    /** 查询订单及版本；不存在明确返回404。 */
    public Map<String, Object> find(String orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM orders WHERE order_id=?", orderId);
        if (rows.isEmpty()) { throw new BusinessException("ORDER_NOT_FOUND", 404, "订单不存在"); }
        return rows.getFirst();
    }

    /** 订单行锁串行化退款、支付与所有会员消费。必须在事务内调用。 */
    public Map<String, Object> lock(String orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM orders WHERE order_id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) { throw new BusinessException("ORDER_NOT_FOUND", 404, "订单不存在"); }
        return rows.getFirst();
    }
}
