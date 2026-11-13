package io.ordivane.demo.simulation;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import io.ordivane.demo.order.BusinessException;

/** 受演示API Bearer保护的local模拟入口，只发送已提交支付事实的副本。 */
@RestController
@Profile("local")
@ConditionalOnProperty(name="demo.simulation.enabled", havingValue="true")
@RequestMapping("/api/v1/simulations/faults")
public class FaultController {
    private final FaultRegistry faults;
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final String prefix;
    /** 注入故障目标与实例隔离拓扑，不改权威业务事务。 */
    public FaultController(FaultRegistry faults, JdbcTemplate jdbc, RabbitTemplate rabbit,
            @Value("${demo.messaging.prefix:ordivane.demo}") String prefix) {
        this.faults = faults; this.jdbc = jdbc; this.rabbit = rabbit; this.prefix = prefix;
    }
    /** 故障请求仅包含一个非空枚举；不接受业务输入替代支付事实。 */
    public record FaultRequest(FaultRegistry.Scenario scenario) { }
    /** 对精确逻辑事件启用一种故障。 */
    @PutMapping("/{eventId}")
    public Map<String, Object> enable(@PathVariable UUID eventId, @RequestBody FaultRequest request) {
        faults.enable(eventId, request.scenario());
        return Map.of("eventId", eventId, "scenario", request.scenario());
    }
    /** 幂等复位；既有失败观察和已提交业务不回滚。 */
    @DeleteMapping("/{eventId}")
    public Map<String, Object> reset(@PathVariable UUID eventId) {
        faults.reset(eventId); return Map.of("eventId", eventId, "enabled", false);
    }
    /** 发布本地支付事实；非法Schema故障仅把副本版本改为字符串，不改Outbox。 */
    @PostMapping("/{eventId}/messages")
    public Map<String, Object> publish(@PathVariable UUID eventId) throws InterruptedException {
        var rows = jdbc.queryForList("SELECT body FROM outbox_event WHERE event_id=?", eventId);
        if (rows.isEmpty()) { throw new BusinessException("NOT_FOUND", 404, "支付事件不存在"); }
        String body = (String) rows.getFirst().get("body");
        if (faults.scenario(eventId) == FaultRegistry.Scenario.INVALID_SCHEMA) {
            body = body.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"");
        }
        var properties = new MessageProperties();
        properties.setContentType("application/json"); properties.setMessageId(eventId.toString());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(prefix + ".events", "order.paid.v1", new Message(body.getBytes(StandardCharsets.UTF_8), properties), correlation);
            if (!correlation.getFuture().get(5, TimeUnit.SECONDS).ack() || correlation.getReturned() != null) {
                throw new BusinessException("PUBLISH_UNCONFIRMED", 503, "消息发布未确认");
            }
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new BusinessException("PUBLISH_UNCONFIRMED", 503, "消息发布未确认");
        }
        return Map.of("eventId", eventId, "published", true);
    }
}
