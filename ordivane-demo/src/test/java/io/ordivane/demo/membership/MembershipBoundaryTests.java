package io.ordivane.demo.membership;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.UUID;
import io.ordivane.demo.messaging.OrderPaid;
import io.ordivane.demo.messaging.MembershipConsumers;
import io.ordivane.demo.order.BusinessException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** 事件边界拒绝未知版本、空字段和隐式权益默认值。 */
class MembershipBoundaryTests {
    @Test
    void preservesJsonVersionTypesAndExactNumericValueOnBothPaths() {
        var json = JsonMapper.builder().build();
        var event = new OrderPaid(UUID.randomUUID(), "ordivane-demo", "OrderPaid", 1,
                OffsetDateTime.now(java.time.ZoneOffset.UTC), new OrderPaid.Payload("boundary-order", "MEMBERSHIP"));
        String body = json.writeValueAsString(event);
        for (boolean recovery : new boolean[]{false, true}) {
            for (String invalid : new String[]{"\"1\"", "\"1.0\"", "\"\"", "1.5",
                    "1.0000000000000000000001", "0.9999999999999999999999", "1e-1000",
                    "true", "false", "null", "[]", "{}", "2"}) {
                var service = mock(MembershipService.class);
                var consumer = new MembershipConsumers(service);
                String input = body.replace("\"schemaVersion\":1", "\"schemaVersion\":" + invalid);
                assertThrows(AmqpRejectAndDontRequeueException.class,
                        () -> deliver(consumer, input, recovery), "非法版本：" + invalid);
                verifyNoInteractions(service);
            }
            var service = mock(MembershipService.class);
            assertThrows(AmqpRejectAndDontRequeueException.class,
                    () -> deliver(new MembershipConsumers(service), body.replace("\"schemaVersion\":1,", ""), recovery));
            verifyNoInteractions(service);
            for (String valid : new String[]{"1", "1.0", "1e0", "10e-1", "1.0000000000000000000000"}) {
                var target = mock(MembershipService.class);
                deliver(new MembershipConsumers(target), body.replace("\"schemaVersion\":1", "\"schemaVersion\":" + valid), recovery);
                verify(target).apply(eq(event), recovery ? any(UUID.class) : isNull());
            }
        }
    }

    /** 直接调用真实两入口，只替换事务服务以断言非法事件不进入业务边界。 */
    private void deliver(MembershipConsumers consumer, String input, boolean recovery) {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        if (recovery) {
            properties.setHeader("executionId", UUID.randomUUID().toString());
            properties.setHeader("recoveryOrigin", "ordivane-platform");
        }
        var message = new Message(input.getBytes(StandardCharsets.UTF_8), properties);
        if (recovery) { consumer.recovery(message); } else { consumer.original(message); }
    }

    @Test
    void rejectsUnsupportedOrMissingBusinessFields() {
        assertThrows(BusinessException.class, () -> new OrderPaid(UUID.randomUUID(), "ordivane-demo", "OrderPaid", 2,
                OffsetDateTime.now(), new OrderPaid.Payload("order-1", "MEMBERSHIP")).validate());
        assertThrows(BusinessException.class, () -> new OrderPaid(null, "ordivane-demo", "OrderPaid", 1,
                OffsetDateTime.now(), new OrderPaid.Payload("order-1", "MEMBERSHIP")).validate());
        for (String value : new String[]{null, "", " ", "OTHER"}) {
            assertThrows(BusinessException.class, () -> OrderPaid.validateTarget("order-1", value));
        }
        assertDoesNotThrow(() -> OrderPaid.validateTarget("order-1", "MEMBERSHIP"));
    }
}
