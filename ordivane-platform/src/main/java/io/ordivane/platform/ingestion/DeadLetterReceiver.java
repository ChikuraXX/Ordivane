package io.ordivane.platform.ingestion;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 单受控队列到source/handler映射；不使用消息头建立安全主体或恢复授权。 */
@Component
public class DeadLetterReceiver {
    private final ReportDecoder decoder;
    private final IngestionStore store;
    private final RabbitListenerEndpointRegistry registry;
    private final String queue;
    private final String source;
    private final String handler;
    private final io.ordivane.platform.audit.AuditService audit;
    public DeadLetterReceiver(ReportDecoder decoder, IngestionStore store, RabbitListenerEndpointRegistry registry,
            io.ordivane.platform.audit.AuditService audit,
            @Value("${ordivane.ingestion.queue:}") String queue,
            @Value("${ordivane.ingestion.source:}") String source,
            @Value("${ordivane.ingestion.handler:membership.activate}") String handler,
            @Value("${ordivane.ingestion.enabled:false}") boolean enabled) {
        this.decoder=decoder; this.store=store; this.registry=registry;
        this.queue=queue; this.source=source; this.handler=handler;
        this.audit=audit;
        if (enabled && (queue.isBlank() || queue.length()>240 || !source.matches("[A-Za-z0-9_.-]{1,128}")
                || !handler.matches("[A-Za-z0-9_.-]{1,128}"))) { throw new IllegalArgumentException("可信队列映射不完整"); }
    }
    /** 手动确认只在事务返回后执行；持久化故障停止本消费者，未确认消息由channel关闭退回broker。 */
    @RabbitListener(id="failure-ingestion", queues="${ordivane.ingestion.queue:ordivane.ingestion.disabled}",
            autoStartup="${ordivane.ingestion.enabled:false}", ackMode="MANUAL", concurrency="1")
    public void receive(Message message, Channel channel) throws IOException {
        try {
            persist(message);
            // 业务事务已提交；审计失败同样不确认，重传由持久化幂等恢复。
            audit.record(new io.ordivane.platform.audit.AuditService.Subject(
                    io.ordivane.platform.audit.AuditService.SubjectType.SOURCE, source),
                    "DLQ_ACCEPT","TRANSPORT",ReportDecoder.hash(message.getBody()),
                    io.ordivane.platform.audit.AuditService.Result.SUCCESS,UUID.randomUUID().toString());
        } catch (RuntimeException failure) {
            // prefetch=1保证停止期间没有后续交付；不NACK循环、不丢弃、不打印原文/头/SQL。
            org.slf4j.LoggerFactory.getLogger(getClass()).error("失败接入或审计未确认，停止消费者，保留未确认消息供人工恢复 failureType={}",failure.getClass().getSimpleName());
            Thread.ofVirtual().name("failure-ingestion-stop").start(() -> {
                try {
                    var container = registry.getListenerContainer("failure-ingestion");
                    if (container != null) { container.stop(); }
                } catch (RuntimeException stopFailure) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).error("失败接入消费者停止失败，需人工停实例");
                }
            });
            return;
        }
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
    /** 可独立测试的持久化边界；非法消息也必须先落安全观察再允许ACK。 */
    public void persist(Message message) {
        var props = message.getMessageProperties();
        boolean trusted = queue.equals(props.getConsumerQueue());
        JsonNode node = null;
        ReportDecoder.Report report = null;
        try {
            node = decoder.parse(message.getBody());
            if (node.has("reportId") || node.has("observationId") || node.has("originalEvent")) {
                report = decoder.report(message.getBody());
                var headers = props.getHeaders();
                ReportDecoder.check(trusted && source.equals(report.source()) && handler.equals(report.handlerId())
                        && queue.equals(report.body().get("deadLetter").get("queue").asString())
                        && report.reportId().toString().equals(headers.get("reportId"))
                        && report.observationId().toString().equals(headers.get("observationId")));
            } else {
                ReportDecoder.check(trusted); decoder.event(node);
                // 任意恢复头不能变成业务授权；这里只保存事件，不执行或重放。
            }
        } catch (IngestionException invalid) { node=null; report=null; }
        if (report != null) {
            try { store.report(report); return; }
            catch (IngestionException conflict) { if (conflict.status()!=409) { throw conflict; } node=null; }
        }
        String messageId = props.getMessageId();
        // messageId仅用于传输去重且限规范UUID；不是reportId、身份或执行授权。
        try { if (messageId != null && !UUID.fromString(messageId).toString().equals(messageId)) { messageId=null; } }
        catch (IllegalArgumentException invalid) { messageId=null; }
        String key = IngestionStore.hash(queue + "\n" + ReportDecoder.hash(message.getBody()) + "\n" + (messageId==null?"":messageId));
        store.fallback(trusted ? source : "unattributed", handler, key, message.getBody(), trusted ? node : null);
    }
}
