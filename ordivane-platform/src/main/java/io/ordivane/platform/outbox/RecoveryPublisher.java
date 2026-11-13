package io.ordivane.platform.outbox;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 独立publisher连接的mandatory/confirm，不更改接入消费者或全局RabbitTemplate。 */
@Component
public class RecoveryPublisher implements ApplicationListener<ContextClosedEvent> {
    private final CachingConnectionFactory connection;
    private final RabbitTemplate rabbit;
    private final int confirmMillis;
    private final JsonMapper json=JsonMapper.builder().build();
    public RecoveryPublisher(ConnectionFactory existing,ApplicationContext context,@Value("${ordivane.execution.confirm-timeout-ms:5000}") int confirmMillis) {
        if(confirmMillis<100 || confirmMillis>10000) throw new IllegalArgumentException("确认超时须为100..10000毫秒");
        if(!(existing instanceof CachingConnectionFactory caching)) throw new IllegalArgumentException("执行发送需要既有CachingConnectionFactory");
        connection=new CachingConnectionFactory(caching.getRabbitConnectionFactory());
        connection.setApplicationContext(context);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);connection.setPublisherReturns(true);
        rabbit=new RabbitTemplate(connection);rabbit.setMandatory(true);this.confirmMillis=confirmMillis;
    }
    /** 私有连接不属于Spring Bean，转发真实关闭事件以释放其deferred channel线程池。 */
    @Override public void onApplicationEvent(ContextClosedEvent event) { connection.onApplicationEvent(event); }
    /** 重建全部消息属性，仅允许当前执行/尝试头；UNKNOWN必须已先提交。 */
    public String publish(ExecutionOutbox.Dispatch dispatch) throws InterruptedException {
        MessageProperties properties=new MessageProperties();properties.setContentType("application/json");properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(json.readTree(dispatch.body()).get("eventId").asString());
        properties.setHeader("executionId",dispatch.lease().executionId().toString());properties.setHeader("recoveryOrigin","ordivane-platform");properties.setHeader("attemptId",dispatch.attemptId().toString());
        var correlation=new CorrelationData(dispatch.attemptId().toString());
        try {
            rabbit.send(dispatch.exchange(),dispatch.routingKey(),new Message(dispatch.body().getBytes(StandardCharsets.UTF_8),properties),correlation);
            var confirm=correlation.getFuture().get(confirmMillis,TimeUnit.MILLISECONDS);
            return confirmedOutcome(confirm,correlation.getReturned()!=null);
        } catch(TimeoutException timeout) { return "CONFIRM_TIMEOUT"; }
        catch(java.util.concurrent.ExecutionException | org.springframework.amqp.AmqpException failure) { return "CONNECTION_LOST"; }
    }
    /** Spring为关闭通道合成的NACK带cause；真正basic.nack不带cause，须分别展示。 */
    static String confirmedOutcome(CorrelationData.Confirm confirm,boolean returned) {
        if(returned) return "RETURNED";
        if(confirm.ack()) return "ACK";
        return confirm.reason()==null?"NACK":"CONNECTION_LOST";
    }
    /** 仅释放本组件创建的连接，不关闭用户服务或共享消费者连接。 */
    @PreDestroy public void close() {
        rabbit.destroy();
        // 先停止物理连接唤醒丢失confirm的等待，再等待私有channel线程池终止。
        connection.stop();connection.destroy();
    }
}
