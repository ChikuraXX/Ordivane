package io.ordivane.demo.processing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import io.ordivane.demo.messaging.OrderPaid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 报告与两种待发通道一次事务持久化；任意输入原文/消息头/异常栈不入报告。 */
@Service
public class FailureReportStore {
    /** 安全错误分类，摘要只来自固定常量。 */
    public enum Error {
        INVALID_MESSAGE("消息格式或恢复头无效"), PROCESSING_FAILED("会员处理未完成"),
        DOWNSTREAM_UNAVAILABLE("模拟下游不可用"),
        COMMITTED_RESPONSE_TIMEOUT("业务已提交但响应超时，需核验业务结果");
        private final String summary;
        Error(String summary) { this.summary = summary; }
    }
    /** 死信与HTTP使用同一不可变报告；关联字段与外层相同。 */
    public record DeadLetter(String queue, UUID reportId, UUID observationId) { }
    /** 各ID职责见FailureReport v1；eventId/originalEvent仅严格信封可用时非null。 */
    public record Report(int schemaVersion, UUID reportId, UUID observationId, String source,
            UUID eventId, String handlerId, UUID executionId, String origin, OffsetDateTime observedAt,
            String errorCode, String errorSummary, OrderPaid originalEvent, String bodySha256, DeadLetter deadLetter) { }
    private final JdbcTemplate jdbc;
    private final String source;
    private final String queue;
    private final JsonMapper json = JsonMapper.builder().build();
    /** 来源遵循BE-020 sourceId语法；发送凭据另行注入，不是演示API凭据。 */
    public FailureReportStore(JdbcTemplate jdbc, @Value("${demo.reports.source:${ORDIVANE_REPORT_SOURCE_ID:ordivane-demo}}") String source,
            @Value("${demo.messaging.prefix:ordivane.demo}") String prefix) {
        if (!source.matches("[A-Za-z0-9_.-]{1,128}") || prefix.length() > 210) {
            throw new IllegalArgumentException("报告来源或拓扑配置无效");
        }
        this.jdbc = jdbc; this.source = source; this.queue = prefix + ".membership.failed";
    }
    /** 消费失败各生成独立观察；ACK之前提交，重发只读取已保存body。 */
    @Transactional
    public UUID record(byte[] body, OrderPaid event, UUID executionId, boolean recovery, Error error) {
        UUID reportId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        var report = new Report(1, reportId, observationId, source, event == null ? null : event.eventId(),
                "membership.activate", executionId, recovery ? "RECOVERY" : "ORIGINAL", OffsetDateTime.now(ZoneOffset.UTC),
                error.name(), error.summary, event, digest(body), new DeadLetter(queue, reportId, observationId));
        jdbc.update("INSERT INTO failure_report(report_id,observation_id,source,event_id,body,topology_prefix) VALUES (?,?,?,?,?,?)",
                reportId, observationId, source, report.eventId(), json.writeValueAsString(report),
                queue.substring(0, queue.length() - ".membership.failed".length()));
        return reportId;
    }
    /** 非法正文仅留下SHA-256，不存任意秘密字段。 */
    private String digest(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("运行时缺少SHA-256", impossible); }
    }
}
