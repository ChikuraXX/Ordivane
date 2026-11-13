package io.ordivane.platform.ingestion;

import io.ordivane.platform.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import static org.assertj.core.api.Assertions.*;

/** 缺少可信队列身份时，即使原文/恢复头看起来有效也只能安全隔离。 */
class DeadLetterTrustTests {
    @Test
    void messageSelfDeclarationCannotReplaceControlledQueueMapping() {
        var store=new RecordingStore();
        var receiver=new DeadLetterReceiver(new ReportDecoder(),store,new RabbitListenerEndpointRegistry(),
                new AuditService(new org.springframework.jdbc.core.JdbcTemplate()),"controlled.failed","registered-source","membership.activate",true);
        var props=new MessageProperties();props.setConsumerQueue("unregistered.queue");
        props.setHeader("source","registered-source");props.setHeader("recoveryOrigin","ordivane-platform");
        props.setHeader("executionId",java.util.UUID.randomUUID().toString());props.setHeader("x-death","trusted-looking");
        byte[] body="{\"source\":\"registered-source\",\"password\":\"never-save\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        receiver.persist(new Message(body,props));
        assertThat(store.source).isEqualTo("unattributed");assertThat(store.handler).isEqualTo("membership.activate");
        assertThat(store.key).matches("[a-f0-9]{64}");assertThat(store.body).isSameAs(body);assertThat(store.event).isNull();
    }
    /** 只记录持久化入参，不连接数据库；避免依赖JVM附加代理。 */
    private static class RecordingStore extends IngestionStore {
        private String source;
        private String handler;
        private String key;
        private byte[] body;
        private tools.jackson.databind.JsonNode event;
        RecordingStore() {
            super(new org.springframework.jdbc.core.JdbcTemplate(),new org.springframework.jdbc.datasource.DataSourceTransactionManager(),new ReportDecoder());
        }
        @Override public void fallback(String source,String handler,String key,byte[] bytes,tools.jackson.databind.JsonNode event) {
            this.source=source;this.handler=handler;this.key=key;this.body=bytes;this.event=event;
        }
        @Override public boolean report(ReportDecoder.Report report) { throw new AssertionError("不可信队列不得接入应用报告"); }
    }
}
