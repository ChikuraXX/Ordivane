package io.ordivane.demo.membership;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import io.ordivane.demo.DemoApplication;
import io.ordivane.demo.messaging.*;
import io.ordivane.demo.order.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真实本机PG/MQ回归；所有业务ID和消息拓扑含独立runId，不消费开发队列。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderMembershipIntegrationTests {
    private final String runId = "BE-010-" + UUID.randomUUID();
    private final String prefix = "ordivane.test.be010." + runId.toLowerCase(Locale.ROOT);
    private final List<String> orderIds = new ArrayList<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private OrderService orders;
    private MembershipService membership;
    private RabbitTemplate rabbit;
    private RabbitAdmin admin;
    private String base;
    private final String token = UUID.randomUUID().toString();

    @BeforeAll
    void startIsolatedApplication() {
        SpringApplication app = new SpringApplication(DemoApplication.class);
        context = app.run("--server.port=0", "--demo.messaging.prefix=" + prefix,
                "--demo.outbox.enabled=false", "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--ORDIVANE_DEMO_API_TOKEN=" + token);
        jdbc = context.getBean(JdbcTemplate.class);
        orders = context.getBean(OrderService.class);
        membership = context.getBean(MembershipService.class);
        rabbit = context.getBean(RabbitTemplate.class);
        admin = new RabbitAdmin(context.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class));
        admin.initialize();
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
        assertEquals("ordivane_demo", jdbc.queryForObject("SELECT current_database()", String.class));
        System.out.println("BE010 runId=" + runId + " prefix=" + prefix + " HTTP=" + base);
    }

    /** 支付事件只从真实事务读取，未调用发布器时broker无该消息。 */
    private OrderPaid paid(String suffix) {
        String id = runId + "-" + suffix;
        orderIds.add(id);
        orders.create(id, "MEMBERSHIP");
        orders.pay(id);
        return json.readValue(jdbc.queryForObject("SELECT body FROM outbox_event WHERE order_id=?", String.class, id), OrderPaid.class);
    }

    @Test
    void duplicateAndConcurrentOriginalRecoveryHaveOneEffect() throws Exception {
        OrderPaid event = paid("concurrent");
        assertEquals(event.eventId(), orders.pay(event.payload().orderId()).get("event_id"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i=0; i<16; i++) {
                UUID executionId = i % 2 == 0 ? null : UUID.randomUUID();
                results.add(pool.submit(() -> { start.await(); return membership.apply(event, executionId); }));
            }
            start.countDown();
            int grants = 0;
            for (var result : results) { if ("GRANTED".equals(result.get(15, TimeUnit.SECONDS))) { grants++; } }
            assertEquals(1, grants);
            assertEquals(1, count("membership_entitlement", event));
            assertEquals(16, count("processing_record", event));
            assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM processing_record WHERE event_id=? AND execution_id IS NOT NULL AND origin='RECOVERY'", Integer.class, event.eventId()));
            System.out.println("AC01 concurrent16 oneGrant=true original8 recovery8=true eventId=" + event.eventId());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void refundAndDatabaseFailureNeverLeavePartialSuccess() {
        OrderPaid refunded = paid("refund");
        orders.refund(refunded.payload().orderId());
        assertEquals("REFUND_REJECTED", membership.apply(refunded, null));
        assertEquals("REFUND_REJECTED", membership.apply(refunded, UUID.randomUUID()));
        assertEquals(0, count("membership_entitlement", refunded));
        assertThrows(BusinessException.class, () -> orders.pay(refunded.payload().orderId()));
        OrderPaid rollback = paid("rollback");
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> tx.execute(status -> {
            membership.apply(rollback, UUID.randomUUID());
            // 在权益和成功记录都写入后制造真实PG异常，验证同事务回滚。
            jdbc.execute("SELECT 1/0");
            return null;
        }));
        assertEquals(0, count("membership_entitlement", rollback));
        assertEquals(0, count("processing_record", rollback));
        assertEquals("GRANTED", membership.apply(rollback, null));
        String id = runId + "-payrollback";
        orderIds.add(id);
        orders.create(id, "MEMBERSHIP");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> tx.execute(status -> {
            orders.pay(id); jdbc.execute("SELECT 1/0"); return null;
        }));
        assertEquals("CREATED", orders.find(id).get("status"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE order_id=?", Integer.class, id));
        System.out.println("AC02 refundRejected=true membershipAndRecordRollback=true paymentOutboxRollback=true");
    }

    @Test
    void committedOutboxMandatoryConfirmAndRealConsumers() throws Exception {
        OrderPaid event = paid("broker");
        String body = json.writeValueAsString(event);
        assertNull(jdbc.queryForObject("SELECT published_at FROM outbox_event WHERE event_id=?", java.sql.Timestamp.class, event.eventId()));
        assertNull(rabbit.receive(prefix + ".membership.original", 100));
        // 无绑定exchange的mandatory退回必须保持待发；只操作本runId自建资源。
        admin.declareExchange(new DirectExchange(prefix + ".unroutable.events"));
        var unroutable = new OutboxPublisher(jdbc, rabbit, prefix + ".unroutable", false);
        assertThrows(IllegalStateException.class, () -> unroutable.publish(event.eventId(), body));
        assertNull(jdbc.queryForObject("SELECT published_at FROM outbox_event WHERE event_id=?", java.sql.Timestamp.class, event.eventId()));
        var publisher = context.getBean(OutboxPublisher.class);
        publisher.publish(event.eventId(), body);
        publisher.publish(event.eventId(), body);
        assertNotNull(jdbc.queryForObject("SELECT published_at FROM outbox_event WHERE event_id=?", java.sql.Timestamp.class, event.eventId()));
        assertEquals(0, count("membership_entitlement", event), "broker确认不能当作业务成功");
        UUID executionId = UUID.randomUUID();
        send(event, true, executionId, 1);
        var registry = context.getBean(RabbitListenerEndpointRegistry.class);
        registry.start();
        await(() -> count("processing_record", event) == 3);
        assertEquals(1, count("membership_entitlement", event));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processing_record WHERE event_id=? AND execution_id=?", Integer.class, event.eventId(), executionId));
        // 未知版本与缺失恢复头进入本次死信拓扑，不产生业务记录。
        send(event, true, null, 1);
        send(event, false, null, 2);
        await(() -> Objects.requireNonNull(admin.getQueueInfo(prefix + ".membership.failed")).getMessageCount() == 2);
        assertEquals(3, count("processing_record", event));
        registry.stop();
        System.out.println("AC03 mandatoryReturnPending=true confirmBeforeBusiness=true duplicateOriginalAndRecovery=true invalidDeadLetters=2 eventId=" + event.eventId());
    }

    @Test
    void stringSchemaVersionCannotGrantOnEitherConsumer() throws Exception {
        var registry = context.getBean(RabbitListenerEndpointRegistry.class);
        registry.start();
        try {
            for (boolean recovery : new boolean[]{false, true}) {
                OrderPaid event = paid(recovery ? "q01-string-recovery" : "q01-string-original");
                long deadBefore = Objects.requireNonNull(admin.getQueueInfo(prefix + ".membership.failed")).getMessageCount();
                String invalid = json.writeValueAsString(event).replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"");
                sendBody(invalid, recovery);
                await(() -> count("processing_record", event) > 0
                        || Objects.requireNonNull(admin.getQueueInfo(prefix + ".membership.failed")).getMessageCount() > deadBefore);
                System.out.println("Q01_STRING path=" + (recovery ? "RECOVERY" : "ORIGINAL")
                        + " eventId=" + event.eventId() + " entitlementCount=" + count("membership_entitlement", event)
                        + " processing=" + jdbc.queryForList("SELECT result,execution_id FROM processing_record WHERE event_id=?", event.eventId()));
                assertEquals(0, count("membership_entitlement", event), "字符串版本不能产生权益");
                assertEquals(0, count("processing_record", event), "非法版本不能产生成功处理记录");
                assertEquals(deadBefore + 1, Objects.requireNonNull(admin.getQueueInfo(prefix + ".membership.failed")).getMessageCount());
            }
        } finally { registry.stop(); }
    }

    @Test
    void schemaVersionMatrixPreservesAllowedNumbersAndRejectsOtherTypes() throws Exception {
        var registry = context.getBean(RabbitListenerEndpointRegistry.class);
        registry.start();
        try {
            for (boolean recovery : new boolean[]{false, true}) {
                String path = recovery ? "recovery" : "original";
                String[] invalidValues = {"\"1.0\"", "\"\"", "1.5", "1.0000000000000000000001",
                        "0.9999999999999999999999", "true", "false", "null", "[]", "{}", "2", "MISSING"};
                for (int i = 0; i < invalidValues.length; i++) {
                    String invalid = invalidValues[i];
                    OrderPaid event = paid("q01-bad-" + path + "-" + i);
                    String original = json.writeValueAsString(event);
                    String body = invalid.equals("MISSING") ? original.replace("\"schemaVersion\":1,", "")
                            : original.replace("\"schemaVersion\":1", "\"schemaVersion\":" + invalid);
                    long deadBefore = Objects.requireNonNull(admin.getQueueInfo(prefix + ".membership.failed")).getMessageCount();
                    sendBody(body, recovery);
                    await(() -> count("processing_record", event) > 0
                            || Objects.requireNonNull(admin.getQueueInfo(prefix + ".membership.failed")).getMessageCount() > deadBefore);
                    assertEquals(0, count("membership_entitlement", event), path + "非法版本：" + invalid);
                    assertEquals(0, count("processing_record", event), path + "非法版本：" + invalid);
                    assertEquals(deadBefore + 1, Objects.requireNonNull(admin.getQueueInfo(prefix + ".membership.failed")).getMessageCount());
                    System.out.println("Q01_REJECT path=" + path + " value=" + invalid + " deadLetter=true entitlement=0 processing=0 eventId=" + event.eventId());
                }
                for (String valid : new String[]{"1", "1.0", "1e0", "10e-1", "1.0000000000000000000000"}) {
                    OrderPaid event = paid("q01-valid-" + path + "-" + UUID.randomUUID().toString().substring(0, 8));
                    sendBody(json.writeValueAsString(event).replace("\"schemaVersion\":1", "\"schemaVersion\":" + valid), recovery);
                    await(() -> count("processing_record", event) == 1);
                    assertEquals(1, count("membership_entitlement", event));
                    assertEquals("GRANTED", jdbc.queryForObject("SELECT result FROM processing_record WHERE event_id=?", String.class, event.eventId()));
                    assertEquals(recovery ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM processing_record WHERE event_id=? AND execution_id IS NOT NULL", Integer.class, event.eventId()));
                    System.out.println("Q01_ACCEPT path=" + path + " value=" + valid + " entitlement=1 result=GRANTED eventId=" + event.eventId());
                }
            }
        } finally { registry.stop(); }
    }

    /** 发送仅改变版本JSON类型的信封；恢复路径保留有效传输头。 */
    private void sendBody(String body, boolean recovery) {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        if (recovery) {
            properties.setHeader("executionId", UUID.randomUUID().toString());
            properties.setHeader("recoveryOrigin", "ordivane-platform");
        }
        rabbit.send(prefix + (recovery ? ".recovery" : ".events"), recovery ? "membership.activate" : "order.paid.v1",
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    @Test
    void httpAuthenticationValidationAndBusinessQueries() throws Exception {
        assertEquals(401, request("GET", "/api/v1/processing-records?eventId=" + UUID.randomUUID(), null, false).statusCode());
        assertEquals(400, request("POST", "/api/v1/orders", "{\"orderId\":null,\"entitlementType\":\"MEMBERSHIP\"}", true).statusCode());
        String id = runId + "-http"; orderIds.add(id);
        assertEquals(200, request("POST", "/api/v1/orders", "{\"orderId\":\"" + id + "\",\"entitlementType\":\"MEMBERSHIP\"}", true).statusCode());
        assertEquals(400, request("POST", "/api/v1/orders", "{\"orderId\":\"" + id + "\",\"entitlementType\":\"MEMBERSHIP\",\"unexpected\":true}", true).statusCode());
        assertEquals(409, request("POST", "/api/v1/orders/" + id + "/refunds", "", true).statusCode());
        assertEquals(200, request("POST", "/api/v1/orders/" + id + "/payments", "", true).statusCode());
        var event = json.readValue(jdbc.queryForObject("SELECT body FROM outbox_event WHERE order_id=?", String.class, id), OrderPaid.class);
        UUID executionId = UUID.randomUUID();
        membership.apply(event, executionId);
        var entitlement = request("GET", "/api/v1/orders/" + id + "/entitlements/MEMBERSHIP", null, true);
        assertEquals(200, entitlement.statusCode());
        assertTrue(json.readTree(entitlement.body()).get("active").asBoolean());
        var records = request("GET", "/api/v1/processing-records?eventId=" + event.eventId() + "&executionId=" + executionId, null, true);
        assertEquals(200, records.statusCode());
        assertEquals("GRANTED", json.readTree(records.body()).get(0).get("result").asString());
        assertDoesNotThrow(() -> java.time.OffsetDateTime.parse(json.readTree(records.body()).get(0).get("processed_at").asString()));
        System.out.println("HTTP_QUERY_SAMPLE entitlement=" + entitlement.body() + " processing=" + records.body());
        assertEquals(200, request("GET", "/api/v1/orders/" + id, null, true).statusCode());
        assertEquals(200, request("POST", "/api/v1/orders/" + id + "/refunds", "", true).statusCode());
        assertFalse(json.readTree(request("GET", "/api/v1/orders/" + id + "/entitlements/MEMBERSHIP", null, true).body()).get("active").asBoolean());
        assertEquals(404, request("GET", "/api/v1/orders/" + runId + "-missing", null, true).statusCode());
        var invalid = request("GET", "/api/v1/processing-records?eventId=bad", null, true);
        assertEquals(400, invalid.statusCode()); assertTrue(invalid.body().contains("traceId"));
        System.out.println("HTTP auth401 validation400 conflict409 missing404 orderPaymentRefundEntitlementProcessing200=true");
    }

    /** 只发送到本次隔离的原始或恢复交换机。 */
    private void send(OrderPaid event, boolean recovery, UUID executionId, int version) {
        var value = new OrderPaid(event.eventId(), event.source(), event.eventType(), version, event.occurredAt(), event.payload());
        var properties = new MessageProperties(); properties.setContentType("application/json");
        if (executionId != null) { properties.setHeader("executionId", executionId.toString()); properties.setHeader("recoveryOrigin", "ordivane-platform"); }
        rabbit.send(prefix + (recovery ? ".recovery" : ".events"), recovery ? "membership.activate" : "order.paid.v1",
                new Message(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8), properties));
    }
    /** 有界等待真实异步业务结果，超时明确失败。 */
    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < until) { Thread.sleep(50); }
        assertTrue(condition.getAsBoolean(), "真实消息消费超时");
    }
    /** 表名为测试内固定常量，订单参数绑定且仅统计自有runId。 */
    private int count(String table, OrderPaid event) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE order_id=?", Integer.class, event.payload().orderId());
    }
    /** 对随机本机端口执行真实HTTP请求，凭据不写日志。 */
    private HttpResponse<String> request(String method, String path, String body, boolean authenticated) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (authenticated) { builder.header("Authorization", "Bearer " + token); }
        builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll
    void stopAndRemoveOnlyOwnedResources() {
        if (context == null) { return; }
        context.getBean(RabbitListenerEndpointRegistry.class).stop();
        for (String suffix : List.of(".membership.original", ".membership.recovery", ".membership.failed")) { admin.deleteQueue(prefix + suffix); }
        for (String suffix : List.of(".events", ".recovery", ".dead", ".unroutable.events")) { admin.deleteExchange(prefix + suffix); }
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(status -> {
            for (String id : orderIds) {
                jdbc.update("DELETE FROM processing_record WHERE order_id=?", id);
                jdbc.update("DELETE FROM membership_entitlement WHERE order_id=?", id);
                jdbc.update("DELETE FROM outbox_event WHERE order_id=?", id);
                jdbc.update("DELETE FROM orders WHERE order_id=?", id);
            }
        });
        System.out.println("CLEANUP ownedOrderIds=" + orderIds + " topology=" + prefix + " only=true");
        context.close();
    }
}
