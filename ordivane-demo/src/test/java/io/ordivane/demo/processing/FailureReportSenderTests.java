package io.ordivane.demo.processing;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** 真实网络确认边界：错误确认/超长/慢响应/永久拒绝均不得伪装成功。 */
class FailureReportSenderTests {
    @Test
    void boundedConfirmationAndPermanentErrors() throws Exception {
        String runId = "BE-011-" + UUID.randomUUID();
        String prefix = "ordivane.test.be011." + runId.toLowerCase(Locale.ROOT);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://"
                + System.getenv().getOrDefault("ORDIVANE_PG_HOST", "127.0.0.1") + ":"
                + System.getenv().getOrDefault("ORDIVANE_PG_PORT", "5432") + "/ordivane_demo", "ordivane_demo", System.getenv("ORDIVANE_DEMO_DB_PASSWORD")));
        var connection = new CachingConnectionFactory(); connection.setUri(System.getenv("ORDIVANE_RABBITMQ_URI"));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        var admin = new RabbitAdmin(connection);
        var exchange = new DirectExchange(prefix + ".dead");
        var queue = new Queue(prefix + ".membership.failed");
        admin.declareExchange(exchange); admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("membership.failed"));
        var mode = new AtomicReference<>("wrong-id");
        var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); receiver.setExecutor(executor);
        receiver.createContext("/api/v1/failure-reports", request -> {
            try {
                request.getRequestBody().readAllBytes();
                String current = mode.get();
                byte[] body = (current.equals("oversized") ? "x".repeat(100000) : "{\"reportId\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(current.equals("denied") ? 401 : 202, body.length);
                if (current.equals("slow")) {
                    request.getResponseBody().write(body, 0, 1); request.getResponseBody().flush();
                    try { Thread.sleep(4000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    request.getResponseBody().write(body, 1, body.length - 1);
                } else { request.getResponseBody().write(body); }
            } catch (java.io.IOException cancelled) { /* 发送方超时/限长主动断开，桩不记录请求内容。 */ }
            finally { request.close(); }
        });
        receiver.start();
        var rabbit = new RabbitTemplate(connection); rabbit.setMandatory(true);
        var sender = new FailureReportSender(jdbc, rabbit, prefix, true,
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/api/v1/failure-reports",
                UUID.randomUUID().toString(), 1, 100);
        try {
            var store = new FailureReportStore(jdbc, "be011.sender-test", prefix);
            for (String scenario : List.of("wrong-id", "oversized", "slow", "denied")) {
                mode.set(scenario);
                UUID id = store.record("{}".getBytes(StandardCharsets.UTF_8), null, null, false, FailureReportStore.Error.INVALID_MESSAGE);
                assertTimeout(Duration.ofSeconds(3), sender::sendPending, "完整响应必须有界：" + scenario);
                assertEquals("STOPPED", jdbc.queryForObject("SELECT http_status FROM failure_report WHERE report_id=?", String.class, id));
                assertEquals(1, jdbc.queryForObject("SELECT http_attempts FROM failure_report WHERE report_id=?", Integer.class, id));
            }
            System.out.println("BE011 sender-boundaries=PASS runId=" + runId + " wrongId oversized slow denied attempts=1");
        } finally {
            sender.close();
            receiver.stop(0); executor.shutdownNow();
            jdbc.update("DELETE FROM failure_report WHERE topology_prefix=?", prefix);
            admin.deleteQueue(queue.getName()); admin.deleteExchange(exchange.getName()); connection.destroy();
        }
    }
}
