package io.ordivane.demo.processing;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 有界、带持久租约的两通道发送器；只读本实例前缀，网络确认丢失允许同ID重复。 */
@Component
public class FailureReportSender {
    /** 固定通道常量用于SQL列名，不能由请求控制。 */
    private enum Channel { http, dead }
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final String prefix;
    private final boolean enabled;
    private final URI endpoint;
    private final String credential;
    private final int maxAttempts;
    private final long delayMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    /** HTTP默认关闭；启用时必须显式提供来源高熵凭据和目标，不接受URL内凭据/查询。 */
    public FailureReportSender(JdbcTemplate jdbc, RabbitTemplate rabbit,
            @Value("${demo.messaging.prefix:ordivane.demo}") String prefix,
            @Value("${demo.reports.enabled:${ORDIVANE_REPORT_ENABLED:false}}") boolean enabled,
            @Value("${demo.reports.endpoint:${ORDIVANE_REPORT_ENDPOINT:}}") String endpoint,
            @Value("${demo.reports.credential:${ORDIVANE_REPORT_SOURCE_CREDENTIAL:}}") String credential,
            @Value("${demo.reports.max-attempts:6}") int maxAttempts,
            @Value("${demo.reports.retry-delay-ms:2000}") long delayMs) {
        this.jdbc = jdbc; this.rabbit = rabbit; this.prefix = prefix; this.enabled = enabled;
        this.credential = credential; this.maxAttempts = maxAttempts; this.delayMs = delayMs;
        if (maxAttempts < 1 || maxAttempts > 20 || delayMs < 100 || delayMs > 60000) {
            throw new IllegalArgumentException("报告重试参数无效");
        }
        this.endpoint = enabled ? validateEndpoint(endpoint, credential) : null;
    }
    /** 禁止非loopback明文HTTP、重定向及URI秘密；HTTPS用于其他主机。 */
    private URI validateEndpoint(String value, String secret) {
        try {
            URI uri = URI.create(value);
            boolean transport = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost()));
            if (!transport || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !"/api/v1/failure-reports".equals(uri.getPath())
                    || !secret.matches("[\\x21-\\x7e]{32,512}")) { throw new IllegalArgumentException(); }
            return uri;
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("报告端点或来源凭据配置无效"); }
    }
    /** 每次最多10条/通道，单网络动作超时2秒/5秒；调度异常不输出任意正文或秘密。 */
    @Scheduled(fixedDelayString="${demo.reports.poll-ms:2000}", initialDelayString="${demo.reports.poll-ms:2000}")
    public void sendPending() {
        try {
            dispatch(Channel.dead);
            if (enabled) { dispatch(Channel.http); }
        } catch (org.springframework.dao.DataAccessException failure) {
            org.slf4j.LoggerFactory.getLogger(FailureReportSender.class).warn("报告发送数据库操作未完成，待发数据保留");
        }
    }
    /** 原子领取一条、网络动作前提交预算；过期租约允许重启接管，无事务跨网络。 */
    private void dispatch(Channel channel) {
        String col = channel.name();
        jdbc.update("UPDATE failure_report SET " + col + "_status='STOPPED' WHERE topology_prefix=? AND "
                + col + "_status='PENDING' AND " + col + "_attempts>=? AND (" + col + "_lease_until IS NULL OR "
                + col + "_lease_until<now())", prefix, maxAttempts);
        for (int i = 0; i < 10 && !Thread.currentThread().isInterrupted(); i++) {
            UUID owner = UUID.randomUUID();
            var rows = jdbc.queryForList("UPDATE failure_report SET " + col + "_attempts=" + col + "_attempts+1, "
                    + col + "_owner=?, " + col + "_lease_until=now()+interval '30 seconds', " + col
                    + "_next_at=now()+(? * LEAST(32,power(2," + col + "_attempts)) * interval '1 millisecond') "
                    + "WHERE report_id=(SELECT report_id FROM failure_report WHERE topology_prefix=? AND "
                    + col + "_status='PENDING' AND " + col + "_attempts<? AND " + col + "_next_at<=now() AND ("
                    + col + "_lease_until IS NULL OR " + col + "_lease_until<now()) ORDER BY created_at LIMIT 1 "
                    + "FOR UPDATE SKIP LOCKED) RETURNING report_id,body," + col + "_attempts",
                    owner, delayMs, prefix, maxAttempts);
            if (rows.isEmpty()) { return; }
            var row = rows.getFirst();
            UUID id = (UUID) row.get("report_id");
            String body = (String) row.get("body");
            boolean sent = false;
            boolean permanent = false;
            try {
                if (channel == Channel.dead) { sent = publishDead(id, body); }
                else {
                    var response = sendHttp(body);
                    {
                        byte[] answer = response.body();
                        int status = response.statusCode();
                        if ((status == 200 || status == 202) && answer.length <= 1024) {
                            sent = id.toString().equals(json.readTree(answer).path("reportId").asText());
                        }
                        permanent = status >= 300 && status < 500 && status != 429;
                    }
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception failure) {
                // 网络失败只记稳定分类，不记录异常正文（其中可能含URL/凭据或消息内容）。
                org.slf4j.LoggerFactory.getLogger(FailureReportSender.class).warn("报告发送未确认 reportId={} channel={}", id, col);
            }
            String status = sent ? "SENT" : permanent || ((Number) row.get(col + "_attempts")).intValue() >= maxAttempts ? "STOPPED" : "PENDING";
            jdbc.update("UPDATE failure_report SET " + col + "_status=?, " + col + "_lease_until=NULL, " + col
                    + "_owner=NULL WHERE report_id=? AND " + col + "_owner=?", status, id, owner);
        }
    }
    /** 独立等待预算覆盖响应正文阶段；JDK请求timeout本身不能保证慢正文按时结束。 */
    private HttpResponse<byte[]> sendHttp(String body) throws Exception {
        var pending = http.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + credential)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), info -> new BoundedConfirmation());
        try { return pending.get(2, TimeUnit.SECONDS); }
        finally { if (!pending.isDone()) { pending.cancel(true); } }
    }
    /** 应用上下文释放时终止自有客户端任务，不留下不受管理的网络工作。 */
    @jakarta.annotation.PreDestroy
    public void close() { http.shutdownNow(); }
    /** 仅缓冲最多1025字节，超限取消订阅；完整响应阶段受HttpRequest超时约束。 */
    private static final class BoundedConfirmation implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value; value.request(1);
        }
        @Override public void onNext(java.util.List<java.nio.ByteBuffer> chunks) {
            for (var chunk : chunks) {
                int count = Math.min(chunk.remaining(), 1025 - bytes.size());
                byte[] part = new byte[count]; chunk.get(part); bytes.writeBytes(part);
                if (bytes.size() >= 1025) { subscription.cancel(); result.complete(bytes.toByteArray()); return; }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    /** 死信为脱敏报告而非未经验证的可重放业务消息；必须同时确认且无mandatory退回。 */
    private boolean publishDead(UUID id, String body) throws Exception {
        var report = json.readTree(body);
        var properties = new MessageProperties();
        properties.setContentType("application/json"); properties.setMessageId(id.toString());
        properties.setHeader("reportId", id.toString());
        properties.setHeader("observationId", report.path("observationId").asText());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(prefix + ".dead", "membership.failed", new Message(body.getBytes(StandardCharsets.UTF_8), properties), correlation);
        return correlation.getFuture().get(5, TimeUnit.SECONDS).ack() && correlation.getReturned() == null;
    }
}
