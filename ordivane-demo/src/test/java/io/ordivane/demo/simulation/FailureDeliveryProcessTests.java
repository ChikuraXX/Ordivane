package io.ordivane.demo.simulation;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 实际子JVM/真实PG和MQ/契约接收桩：不能替代BE-030真实平台接入验收。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailureDeliveryProcessTests {
    private final String runId = "BE-011-" + UUID.randomUUID();
    private final String prefix = "ordivane.test.be011." + runId.toLowerCase(Locale.ROOT);
    private final String source = "be011." + UUID.randomUUID();
    private final String token = UUID.randomUUID().toString();
    private final String credential = UUID.randomUUID().toString() + UUID.randomUUID();
    private final String sensitive = "secret-" + UUID.randomUUID();
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final List<String> orders = new ArrayList<>();
    private final List<Long> pids = new ArrayList<>();
    private final List<Map<String, Object>> evidence = new ArrayList<>();
    private final ConcurrentHashMap<UUID, List<String>> received = new ConcurrentHashMap<>();
    private final AtomicInteger receiverStatus = new AtomicInteger(202);
    private final AtomicInteger receiverCount = new AtomicInteger();
    private final Path output = Path.of("../workflow/backend/tasks/BE-011/evidence").toAbsolutePath().normalize();
    private JdbcTemplate jdbc;
    private CachingConnectionFactory connection;
    private RabbitTemplate rabbit;
    private RabbitAdmin admin;
    private Process child;
    private HttpServer receiver;
    private int receiverPort;
    private int sequence;

    @BeforeAll
    void connectOnlyToAuthorizedLocalDependencies() throws Exception {
        String host = System.getenv().getOrDefault("ORDIVANE_PG_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("ORDIVANE_PG_PORT", "5432");
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://" + host + ":" + port + "/ordivane_demo",
                "ordivane_demo", System.getenv("ORDIVANE_DEMO_DB_PASSWORD")));
        assertEquals("ordivane_demo", jdbc.queryForObject("SELECT current_database()", String.class));
        connection = new CachingConnectionFactory(); connection.setUri(System.getenv("ORDIVANE_RABBITMQ_URI"));
        rabbit = new RabbitTemplate(connection); admin = new RabbitAdmin(connection);
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { receiverPort = socket.getLocalPort(); }
        Files.createDirectories(output);
        System.out.println("BE011 runId=" + runId + " topology=" + prefix + " receiverPort=" + receiverPort);
    }

    @Test
    void threeFaultsResetPersistenceRestartAndBoundedDelivery() throws Exception {
        try {
            start(true, true, 6, 2000);
            UUID unavailable = paid("unavailable");
            fault(unavailable, "DOWNSTREAM_UNAVAILABLE");
            publish(unavailable); publish(unavailable);
            await(() -> reportRows().size() == 2, "独立两次失败持久化");
            assertEquals(0, count("membership_entitlement", unavailable));
            assertEquals(0, count("processing_record", unavailable));
            var saved = reportRows();
            assertNotEquals(saved.get(0).get("report_id"), saved.get(1).get("report_id"));
            assertNotEquals(saved.get(0).get("observation_id"), saved.get(1).get("observation_id"));
            await(() -> reportRows().stream().allMatch(row -> ((Number) row.get("http_attempts")).intValue() >= 1), "接收端不可用后保留待发");
            assertTrue(reportRows().stream().allMatch(row -> "PENDING".equals(row.get("http_status"))));
            var stableBodies = new HashMap<UUID, String>();
            saved.forEach(row -> stableBodies.put((UUID) row.get("report_id"), (String) row.get("body")));
            long first = child.pid();
            crash();
            assertEquals(2, reportRows().size(), "实际进程停止后报告不丢失");
            startReceiver();
            start(true, true, 6, 2000);
            assertNotEquals(first, child.pid());
            await(() -> reportRows().stream().allMatch(row -> "SENT".equals(row.get("http_status"))), "进程重启/接收恢复后发送");
            stableBodies.forEach((id, body) -> {
                assertTrue(received.containsKey(id));
                assertTrue(received.get(id).stream().allMatch(body::equals), "报告ID、观察和时间重发稳定");
            });
            evidence.add(Map.of("case", "unavailable-crash-restart-recover", "result", "PASS", "oldPid", first,
                    "newPid", child.pid(), "reports", stableBodies.keySet().stream().map(UUID::toString).toList()));
            // 重启清除实例内故障；再次显式启用/复位验证原目标可正常发送。
            fault(unavailable, "DOWNSTREAM_UNAVAILABLE"); reset(unavailable); publish(unavailable);
            await(() -> count("membership_entitlement", unavailable) == 1, "下游故障复位");

            UUID committed = paid("committed");
            fault(committed, "COMMITTED_RESPONSE_TIMEOUT"); publish(committed);
            await(() -> reportsFor(committed) == 1, "提交后响应超时观察");
            assertEquals(1, count("membership_entitlement", committed));
            assertEquals(1, count("processing_record", committed));
            Thread.sleep(600);
            assertEquals(1, count("processing_record", committed), "失败不无限业务重入队");
            var timeoutReport = reportRows().stream().filter(row -> committed.equals(row.get("event_id"))).findFirst().orElseThrow();
            assertEquals("COMMITTED_RESPONSE_TIMEOUT", json.readTree((String) timeoutReport.get("body")).path("errorCode").asText());
            reset(committed); publish(committed);
            await(() -> count("processing_record", committed) == 2, "提交超时复位后重复消费");
            assertEquals(1, count("membership_entitlement", committed));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processing_record WHERE event_id=? AND result='ALREADY_GRANTED'", Integer.class, committed));

            UUID invalid = paid("schema");
            int before = reportRows().size();
            fault(invalid, "INVALID_SCHEMA"); publish(invalid);
            await(() -> reportRows().size() == before + 1, "非法Schema由模拟生产消息触发");
            assertEquals(0, count("processing_record", invalid)); assertEquals(0, count("membership_entitlement", invalid));
            assertEquals(1, json.readTree(jdbc.queryForObject("SELECT body FROM outbox_event WHERE event_id=?", String.class, invalid)).path("schemaVersion").asInt());
            reset(invalid); publish(invalid);
            await(() -> count("membership_entitlement", invalid) == 1, "非法Schema复位");

            // 额外真实非法消息：任意正文秘密、传输头均不能进入报告/新死信。
            sendRaw("{\"schemaVersion\":\"1\",\"credential\":\"" + sensitive + "\"}", false, null);
            await(() -> reportRows().size() == before + 2, "非法正文脱敏");
            q01AndRecoveryRefundRegression();
            await(() -> reportRows().stream().allMatch(row -> "SENT".equals(row.get("dead_status"))), "全部死信确认");
            verifyDeadLettersAndRedaction();
            evidence.add(Map.of("case", "three-faults-reset-q01-refund-dlq-redaction", "result", "PASS", "reportCount", reportRows().size()));

            // 相同reportId的确认丢失：先接收却返回503，再确认；两次正文必须逐字节相同。
            receiverStatus.set(503);
            UUID retry = paid("confirmation-lost"); fault(retry, "DOWNSTREAM_UNAVAILABLE"); publish(retry);
            await(() -> reportsFor(retry) == 1, "新增重试观察");
            UUID retryId = (UUID) reportRows().stream().filter(row -> retry.equals(row.get("event_id"))).findFirst().orElseThrow().get("report_id");
            await(() -> received.containsKey(retryId), "接收桩暂不确认");
            receiverStatus.set(202);
            await(() -> "SENT".equals(jdbc.queryForObject("SELECT http_status FROM failure_report WHERE report_id=?", String.class, retryId)), "同ID重发确认");
            assertTrue(received.get(retryId).size() >= 2);
            assertEquals(1, new HashSet<>(received.get(retryId)).size());
            evidence.add(Map.of("case", "stable-report-retry", "result", "PASS", "reportId", retryId.toString(), "received", received.get(retryId).size()));

            // 错误2xx/错误ID不当成功；耗尽上限后跨实际重启仍不继续发。
            crash(); receiverStatus.set(204); start(true, true, 2, 100);
            UUID exhausted = paid("bounded"); fault(exhausted, "DOWNSTREAM_UNAVAILABLE"); publish(exhausted);
            await(() -> reportsFor(exhausted) == 1, "有限发送观察");
            UUID exhaustedId = (UUID) reportRows().stream().filter(row -> exhausted.equals(row.get("event_id"))).findFirst().orElseThrow().get("report_id");
            await(() -> "STOPPED".equals(jdbc.queryForObject("SELECT http_status FROM failure_report WHERE report_id=?", String.class, exhaustedId)), "预算耗尽停止");
            assertEquals(2, jdbc.queryForObject("SELECT http_attempts FROM failure_report WHERE report_id=?", Integer.class, exhaustedId));
            crash(); receiverStatus.set(202); start(true, true, 2, 100);
            Thread.sleep(700);
            assertEquals(2, jdbc.queryForObject("SELECT http_attempts FROM failure_report WHERE report_id=?", Integer.class, exhaustedId));
            assertEquals("STOPPED", jdbc.queryForObject("SELECT http_status FROM failure_report WHERE report_id=?", String.class, exhaustedId));
            evidence.add(Map.of("case", "bounded-sends-persist-after-restart", "result", "PASS", "attempts", 2));
        } finally { stop(); }
    }

    @Test
    void explicitEnableCannotExposeFaultApiOutsideLocal() throws Exception {
        try {
            start(false, true, 6, 2000);
            assertTrue(request("PUT", "/api/v1/simulations/faults/" + UUID.randomUUID(), "{\"scenario\":\"DOWNSTREAM_UNAVAILABLE\"}").statusCode() >= 400);
            stop();
            start(true, false, 6, 2000);
            assertTrue(request("PUT", "/api/v1/simulations/faults/" + UUID.randomUUID(), "{\"scenario\":\"DOWNSTREAM_UNAVAILABLE\"}").statusCode() >= 400);
            evidence.add(Map.of("case", "non-local-and-local-disabled", "result", "PASS"));
        } finally { stop(); }
    }

    /** 真实双入口Q01类型矩阵与退款恢复；报告化死信不改变原业务校验。 */
    private void q01AndRecoveryRefundRegression() throws Exception {
        int index = 0;
        for (boolean recovery : new boolean[]{false, true}) {
            for (String version : List.of("\"1\"", "1.5", "1.0000000000000000000001", "true", "null", "[]", "{}", "2")) {
                UUID event = paid("q01-" + index++);
                String body = jdbc.queryForObject("SELECT body FROM outbox_event WHERE event_id=?", String.class, event);
                int before = reportRows().size();
                sendRaw(body.replace("\"schemaVersion\":1", "\"schemaVersion\":" + version), recovery, recovery ? UUID.randomUUID() : null);
                await(() -> reportRows().size() == before + 1, "Q01非法类型");
                assertEquals(0, count("membership_entitlement", event)); assertEquals(0, count("processing_record", event));
            }
            for (String version : List.of("1", "1.0", "1e0", "10e-1", "1.0000000000000000000000")) {
                UUID event = paid("valid-" + index++);
                UUID execution = recovery ? UUID.randomUUID() : null;
                String body = jdbc.queryForObject("SELECT body FROM outbox_event WHERE event_id=?", String.class, event);
                sendRaw(body.replace("\"schemaVersion\":1", "\"schemaVersion\":" + version), recovery, execution);
                await(() -> count("membership_entitlement", event) == 1, "Q01合法数值");
                assertEquals(execution, jdbc.queryForObject("SELECT execution_id FROM processing_record WHERE event_id=?", UUID.class, event));
            }
        }
        UUID refunded = paid("refund");
        String orderId = jdbc.queryForObject("SELECT order_id FROM outbox_event WHERE event_id=?", String.class, refunded);
        assertEquals(200, request("POST", "/api/v1/orders/" + orderId + "/refunds", "").statusCode());
        fault(refunded, "COMMITTED_RESPONSE_TIMEOUT");
        publish(refunded);
        sendRaw(jdbc.queryForObject("SELECT body FROM outbox_event WHERE event_id=?", String.class, refunded), true, UUID.randomUUID());
        await(() -> count("processing_record", refunded) == 2, "退款双入口");
        assertEquals(0, count("membership_entitlement", refunded)); assertEquals(0, reportsFor(refunded));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM processing_record WHERE event_id=? AND result='REFUND_REJECTED'", Integer.class, refunded));
    }

    /** 桩仅验证冻结契约与发送身份，不接触平台库、不声称真实来源鉴权通过。 */
    private void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", receiverPort), 0);
        receiver.createContext("/api/v1/failure-reports", exchange -> {
            try {
                byte[] input = exchange.getRequestBody().readNBytes(32769);
                if (!"Bearer ".concat(credential).equals(exchange.getRequestHeaders().getFirst("Authorization")) || input.length > 32768) {
                    exchange.sendResponseHeaders(401, -1); return;
                }
                String body = new String(input, StandardCharsets.UTF_8);
                var tree = json.readTree(body);
                if (!source.equals(tree.path("source").asText()) || tree.path("schemaVersion").asInt() != 1) {
                    exchange.sendResponseHeaders(403, -1); return;
                }
                UUID id = UUID.fromString(tree.path("reportId").asText());
                received.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>()).add(body);
                receiverCount.incrementAndGet();
                int status = receiverStatus.get();
                if (status == 204) { exchange.sendResponseHeaders(status, -1); }
                else {
                    byte[] answer = json.writeValueAsBytes(Map.of("reportId", id));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, answer.length); exchange.getResponseBody().write(answer);
                }
            } finally { exchange.close(); }
        });
        receiver.start();
    }

    /** 启动前绑定检查8081；不占用/停止未知服务；凭据只注入子进程环境。 */
    private void start(boolean local, boolean faults, int max, int delay) throws Exception {
        try (var socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", 8081)); }
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"), "io.ordivane.demo.DemoApplication", "--server.port=8081",
                "--demo.messaging.prefix=" + prefix, "--demo.outbox.enabled=false", "--demo.simulation.enabled=" + faults,
                "--demo.reports.enabled=true", "--demo.reports.source=" + source,
                "--demo.reports.endpoint=http://127.0.0.1:" + receiverPort + "/api/v1/failure-reports",
                "--demo.reports.max-attempts=" + max, "--demo.reports.retry-delay-ms=" + delay, "--demo.reports.poll-ms=200",
                "--spring.profiles.active=" + (local ? "local" : "be011-nonlocal")));
        var builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.resolve(runId + "-child-" + (++sequence) + ".log").toFile());
        builder.environment().put("ORDIVANE_DEMO_API_TOKEN", token);
        builder.environment().put("ORDIVANE_REPORT_SOURCE_CREDENTIAL", credential);
        child = builder.start(); pids.add(child.pid());
        await(() -> {
            if (!child.isAlive()) { return false; }
            try { return request("GET", "/actuator/health", null).statusCode() == 200; }
            catch (Exception notReady) { return false; }
        }, "子JVM启动，见本轮child日志");
        System.out.println("BE011 childPid=" + child.pid() + " port=8081 local=" + local + " simulation=" + faults);
    }
    /** 强制结束本测试创建的实际子JVM，模拟进程崩溃，不碰其他PID。 */
    private void crash() throws Exception {
        if (child != null && child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS)); }
    }
    /** 正常关闭本测试子JVM；未退出则只对同一自有PID强制收尾。 */
    private void stop() throws Exception {
        if (child != null && child.isAlive()) { child.destroy(); if (!child.waitFor(10, TimeUnit.SECONDS)) { crash(); } }
    }
    /** 通过真实业务API创建/支付，订单仅属于本轮runId。 */
    private UUID paid(String suffix) throws Exception {
        String id = runId + "-" + suffix; orders.add(id);
        assertEquals(200, request("POST", "/api/v1/orders", json.writeValueAsString(Map.of("orderId", id, "entitlementType", "MEMBERSHIP"))).statusCode());
        assertEquals(200, request("POST", "/api/v1/orders/" + id + "/payments", "").statusCode());
        return jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE order_id=?", UUID.class, id);
    }
    private void fault(UUID event, String scenario) throws Exception {
        assertEquals(200, request("PUT", "/api/v1/simulations/faults/" + event, json.writeValueAsString(Map.of("scenario", scenario))).statusCode());
    }
    private void reset(UUID event) throws Exception { assertEquals(200, request("DELETE", "/api/v1/simulations/faults/" + event, null).statusCode()); }
    private void publish(UUID event) throws Exception { assertEquals(200, request("POST", "/api/v1/simulations/faults/" + event + "/messages", "").statusCode()); }
    /** 只向本测试明确声明的两条绑定发送；任意秘密头不得传播到报告死信。 */
    private void sendRaw(String body, boolean recovery, UUID execution) {
        var properties = new MessageProperties(); properties.setContentType("application/json");
        properties.setHeader("Authorization", sensitive);
        if (recovery) { properties.setHeader("executionId", execution.toString()); properties.setHeader("recoveryOrigin", "ordivane-platform"); }
        rabbit.send(prefix + (recovery ? ".recovery" : ".events"), recovery ? "membership.activate" : "order.paid.v1",
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }
    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081" + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private List<Map<String, Object>> reportRows() { return jdbc.queryForList("SELECT * FROM failure_report WHERE topology_prefix=? ORDER BY created_at", prefix); }
    private int reportsFor(UUID event) { return jdbc.queryForObject("SELECT count(*) FROM failure_report WHERE topology_prefix=? AND event_id=?", Integer.class, prefix, event); }
    /** 表名为本测试固定常量，值始终绑定；不使用模糊前缀清理业务数据。 */
    private int count(String table, UUID event) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE event_id=?", Integer.class, event); }
    private void await(java.util.function.BooleanSupplier check, String label) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        while (!check.getAsBoolean() && System.nanoTime() < until) { Thread.sleep(100); }
        assertTrue(check.getAsBoolean(), label);
    }
    /** 逐条消费自建死信核对数据库同一报告与关联ID，禁止记录或断言回显秘密。 */
    private void verifyDeadLettersAndRedaction() {
        Set<UUID> found = new HashSet<>();
        for (int i = 0; i < reportRows().size(); i++) {
            Message dead = rabbit.receive(prefix + ".membership.failed", 1000); assertNotNull(dead);
            String body = new String(dead.getBody(), StandardCharsets.UTF_8);
            assertFalse(body.contains(sensitive)); assertFalse(body.contains(credential)); assertFalse(body.contains(token));
            var tree = json.readTree(body); UUID id = UUID.fromString(tree.path("reportId").asText());
            assertEquals(body, jdbc.queryForObject("SELECT body FROM failure_report WHERE report_id=?", String.class, id));
            assertEquals(id.toString(), tree.path("deadLetter").path("reportId").asText());
            assertEquals(id.toString(), dead.getMessageProperties().getHeader("reportId"));
            assertEquals(tree.path("observationId").asText(), dead.getMessageProperties().getHeader("observationId"));
            assertNull(dead.getMessageProperties().getHeader("Authorization"));
            if ("INVALID_MESSAGE".equals(tree.path("errorCode").asText())) { assertTrue(tree.path("originalEvent").isNull()); }
            found.add(id);
        }
        assertEquals(reportRows().size(), found.size());
        assertNull(rabbit.receive(prefix + ".membership.original", 100));
        assertNull(rabbit.receive(prefix + ".membership.recovery", 100));
    }

    @AfterAll
    void cleanOnlyOwnedResourcesAndWriteEvidence() throws Exception {
        stop(); if (receiver != null) { receiver.stop(0); }
        var finalRows = reportRows().stream().map(row -> Map.of("reportId", row.get("report_id").toString(),
                "httpStatus", row.get("http_status"), "httpAttempts", row.get("http_attempts"), "deadStatus", row.get("dead_status"))).toList();
        jdbc.update("DELETE FROM failure_report WHERE topology_prefix=?", prefix);
        for (String id : orders) {
            jdbc.update("DELETE FROM processing_record WHERE order_id=?", id); jdbc.update("DELETE FROM membership_entitlement WHERE order_id=?", id);
            jdbc.update("DELETE FROM outbox_event WHERE order_id=?", id); jdbc.update("DELETE FROM orders WHERE order_id=?", id);
        }
        for (String suffix : List.of(".membership.original", ".membership.recovery", ".membership.failed")) { admin.deleteQueue(prefix + suffix); assertNull(admin.getQueueInfo(prefix + suffix)); }
        for (String suffix : List.of(".events", ".recovery", ".dead")) { admin.deleteExchange(prefix + suffix); }
        assertEquals(0, reportRows().size());
        for (String id : orders) { assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orders WHERE order_id=?", Integer.class, id)); }
        assertTrue(pids.stream().noneMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)));
        connection.destroy();
        Files.writeString(output.resolve(runId + "-results.json"), json.writeValueAsString(Map.of("runId", runId,
                "topology", prefix, "cases", evidence, "reportsBeforeCleanup", finalRows, "childPids", pids,
                "ownedOrders", orders, "cleanup", "PASS", "contractStubOnly", true, "platformIntegration", "NOT_RUN")));
        System.out.println("BE011 cleanup=PASS runId=" + runId + " childPids=" + pids + " cases=" + evidence.size());
    }
}
