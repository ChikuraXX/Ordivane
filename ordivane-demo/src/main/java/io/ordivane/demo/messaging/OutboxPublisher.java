package io.ordivane.demo.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫描已提交Outbox；发布确认后更新，崩溃窗口允许重复，绝不先标成功。 */
@Component
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final String prefix;
    private final boolean enabled;
    /** 使用BOM管理的AMQP确认机制；不在支付事务内等待broker。 */
    public OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit,
            @Value("${demo.messaging.prefix:ordivane.demo}") String prefix,
            @Value("${demo.outbox.enabled:true}") boolean enabled) {
        this.jdbc = jdbc; this.rabbit = rabbit; this.prefix = prefix; this.enabled = enabled;
    }
    /** 固定延迟、每批最多20条；未确认保持待发，后续批次再试。 */
    @Scheduled(fixedDelayString="${demo.outbox.delay-ms:2000}", initialDelayString="${demo.outbox.delay-ms:2000}")
    public void publishPending() {
        if (!enabled) { return; }
        for (var row : jdbc.queryForList("SELECT event_id,body FROM outbox_event WHERE published_at IS NULL ORDER BY created_at LIMIT 20")) {
            try { publish((UUID) row.get("event_id"), (String) row.get("body")); }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); return; }
                org.slf4j.LoggerFactory.getLogger(OutboxPublisher.class).warn("Outbox发布未确认 eventId={}", row.get("event_id"), failure);
                return;
            }
        }
    }
    /** 等待broker确认和mandatory退回结果后标记；该标记不是业务成功。 */
    public void publish(UUID eventId, String body) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setMessageId(eventId.toString());
        properties.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(prefix + ".events", "order.paid.v1", new Message(body.getBytes(StandardCharsets.UTF_8), properties), correlation);
        var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.ack() || correlation.getReturned() != null) { throw new IllegalStateException("broker未确认或消息不可路由"); }
        jdbc.update("UPDATE outbox_event SET published_at=COALESCE(published_at,now()) WHERE event_id=?", eventId);
    }
}
