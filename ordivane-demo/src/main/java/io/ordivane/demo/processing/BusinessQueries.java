package io.ordivane.demo.processing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.ordivane.demo.messaging.OrderPaid;
import io.ordivane.demo.order.OrderService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 只读业务结果，不把消息确认当作权益证明。 */
@RestController
@RequestMapping("/api/v1")
public class BusinessQueries {
    private final JdbcTemplate jdbc;
    private final OrderService orders;
    /** 使用演示库的已提交数据。 */
    public BusinessQueries(JdbcTemplate jdbc, OrderService orders) { this.jdbc = jdbc; this.orders = orders; }
    /** 返回权益及当前订单状态，退款后历史权益不代表当前有效。 */
    @GetMapping("/orders/{orderId}/entitlements/{entitlementType}")
    public Map<String, Object> entitlement(@PathVariable String orderId, @PathVariable String entitlementType) {
        OrderPaid.validateTarget(orderId, entitlementType);
        var order = orders.find(orderId);
        var rows = jdbc.queryForList("SELECT * FROM membership_entitlement WHERE order_id=? AND entitlement_type=?", orderId, entitlementType);
        return Map.of("orderId", orderId, "entitlementType", entitlementType, "orderStatus", order.get("status"),
                "granted", !rows.isEmpty(), "active", !rows.isEmpty() && "PAID".equals(order.get("status")), "entitlements", rows);
    }
    /** 查询指定事件的最近100次尝试，可精确过滤恢复执行。 */
    @GetMapping("/processing-records")
    public List<Map<String, Object>> records(@RequestParam UUID eventId, @RequestParam(required=false) UUID executionId) {
        if (executionId == null) {
            return jdbc.queryForList("SELECT * FROM processing_record WHERE event_id=? ORDER BY processed_at DESC,attempt_id LIMIT 100", eventId);
        }
        return jdbc.queryForList("SELECT * FROM processing_record WHERE event_id=? AND execution_id=? ORDER BY processed_at DESC,attempt_id LIMIT 100", eventId, executionId);
    }
}
