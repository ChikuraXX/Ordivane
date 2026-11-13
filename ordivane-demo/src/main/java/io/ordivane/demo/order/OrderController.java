package io.ordivane.demo.order;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 模拟订单API，所有业务请求均需演示服务凭据。 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;
    /** 订单创建参数；不接受金额或隐式权益。 */
    public record CreateOrder(String orderId, String entitlementType) { }
    /** 注入订单事务服务。 */
    public OrderController(OrderService orders) { this.orders = orders; }
    /** 幂等创建订单。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateOrder input) { return orders.create(input.orderId(), input.entitlementType()); }
    /** 支付并持久化待发事件。 */
    @PostMapping("/{orderId}/payments")
    public Map<String, Object> pay(@PathVariable String orderId) { return orders.pay(orderId); }
    /** 退款仅改变订单状态，保留历史证据。 */
    @PostMapping("/{orderId}/refunds")
    public Map<String, Object> refund(@PathVariable String orderId) { return orders.refund(orderId); }
    /** 查询权威订单状态。 */
    @GetMapping("/{orderId}")
    public Map<String, Object> find(@PathVariable String orderId) { return orders.find(orderId); }
}
