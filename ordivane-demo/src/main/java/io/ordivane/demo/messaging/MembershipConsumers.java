package io.ordivane.demo.messaging;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.Optional;
import io.ordivane.demo.membership.MembershipService;
import io.ordivane.demo.processing.FailureReportStore;
import io.ordivane.demo.simulation.FaultRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 两条传输路径仅解析信封和恢复头，共用会员事务；异常进入专用死信队列。 */
@Component
public class MembershipConsumers {
    private final MembershipService membership;
    private final FailureReportStore reports;
    private final Optional<FaultRegistry> faults;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    /** 注入代理事务服务，返回后容器才ACK。 */
    @Autowired
    public MembershipConsumers(MembershipService membership, FailureReportStore reports, Optional<FaultRegistry> faults) {
        this.membership = membership; this.reports = reports; this.faults = faults;
    }
    /** 既有纯解码边界测试入口，不启动报告持久化；生产装配使用上方三参数构造器。 */
    public MembershipConsumers(MembershipService membership) { this(membership, null, Optional.empty()); }
    /** 原始消息禁止携带恢复权限元数据。 */
    @RabbitListener(queues="${demo.messaging.prefix:ordivane.demo}.membership.original")
    public void original(Message message) { consume(message, false); }
    /** 固定会员恢复目标必须同时提供恢复来源与执行UUID。 */
    @RabbitListener(queues="${demo.messaging.prefix:ordivane.demo}.membership.recovery")
    public void recovery(Message message) { consume(message, true); }
    /** 拒绝未知版本、超长消息和无效恢复头，不盲目重入队。 */
    private void consume(Message message, boolean recovery) {
        OrderPaid event = null;
        UUID executionId = null;
        var error = FailureReportStore.Error.INVALID_MESSAGE;
        try {
            if (message.getBody().length > 16384 || !"application/json".equals(message.getMessageProperties().getContentType())) {
                throw new IllegalArgumentException("消息格式无效");
            }
            var headers = message.getMessageProperties().getHeaders();
            if (recovery) {
                if (!"ordivane-platform".equals(headers.get("recoveryOrigin")) || !(headers.get("executionId") instanceof String id)) {
                    throw new IllegalArgumentException("恢复头无效");
                }
                executionId = UUID.fromString(id);
                if (!executionId.toString().equals(id)) { throw new IllegalArgumentException("恢复UUID无效"); }
            } else if (headers.containsKey("executionId") || headers.containsKey("recoveryOrigin")) {
                throw new IllegalArgumentException("原始消息含恢复头");
            }
            var envelope = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            var version = envelope.get("schemaVersion");
            // 在Integer绑定前保持JSON类型；精确比较允许1.0/1e0，避免小数截断或浮点舍入变成版本1。
            if (version == null || !version.isNumber() || version.decimalValue().compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalArgumentException("事件版本必须为数值1");
            }
            var decoded = json.treeToValue(envelope, OrderPaid.class);
            decoded.validate();
            event = decoded;
            error = FailureReportStore.Error.PROCESSING_FAILED;
            var scenario = faults.map(registry -> registry.scenario(decoded.eventId())).orElse(null);
            if (scenario == FaultRegistry.Scenario.DOWNSTREAM_UNAVAILABLE) {
                error = FailureReportStore.Error.DOWNSTREAM_UNAVAILABLE;
                throw new IllegalStateException("模拟下游不可用");
            }
            String result = membership.apply(event, executionId);
            // 代理事务已返回并提交后才模拟响应丢失；退款明确拒绝保持原有成功ACK语义。
            if (scenario == FaultRegistry.Scenario.COMMITTED_RESPONSE_TIMEOUT && !"REFUND_REJECTED".equals(result)) {
                error = FailureReportStore.Error.COMMITTED_RESPONSE_TIMEOUT;
                throw new IllegalStateException("模拟提交后响应超时");
            }
        } catch (RuntimeException failure) {
            if (reports == null) { throw new AmqpRejectAndDontRequeueException("会员处理失败"); }
            try {
                reports.record(message.getBody(), event, executionId, recovery, error);
                // 本次失败观察和死信待发均已提交，容器ACK；发送失败由持久化预算重试。
            } catch (RuntimeException persistenceFailure) {
                // 数据库不可用时broker仍按既有DLX保留原消息，不无限重入队，不输出输入/异常正文。
                throw new AmqpRejectAndDontRequeueException("失败报告未持久化，消息转broker死信");
            }
        }
    }
}
