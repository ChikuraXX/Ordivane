package io.ordivane.platform.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** 唯一约束及事务锁保证并发接入；方法返回时数据库已提交。 */
@Service
public class IngestionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReportDecoder decoder;
    public IngestionStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ReportDecoder decoder) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.decoder = decoder;
    }
    /** true首次接入，false同内容重传；内容冲突保留已有观察并返回409。 */
    public boolean report(ReportDecoder.Report r) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            lock(r.source() + ":report:" + r.reportId());
            var existing = jdbc.queryForList("SELECT body FROM failure_observation WHERE source=? AND report_id=?", String.class, r.source(), r.reportId());
            if (!existing.isEmpty()) {
                if (!existing.getFirst().equals(r.canonical())) {
                    throw new IngestionException(409, "REPORT_CONFLICT", "同一报告标识的内容冲突");
                }
                return false;
            }
            UUID incident = r.eventId() == null ? null : incident(r.source(), r.eventId(), r.handlerId(), r.event());
            boolean conflict = incident != null && eventConflict(r.source(), r.eventId(), r.event());
            jdbc.update("""
                    INSERT INTO failure_observation(id,source,report_id,observation_id,incident_id,event_id,handler_id,
                    kind,body,body_sha256,canonical_sha256,event_conflict) VALUES (?,?,?,?,?,?,?,'REPORT',?,?,?,?)
                    """, UUID.randomUUID(), r.source(), r.reportId(), r.observationId(), incident, r.eventId(), r.handlerId(),
                    r.canonical(), r.body().get("bodySha256").asString(), hash(r.canonical()), conflict);
            return true;
        }));
    }
    /** 原消息兜底无reportId；可信拓扑限定source/handler，非法正文只存hash。 */
    public void fallback(String source, String handler, String key, byte[] bytes, JsonNode event) {
        tx.executeWithoutResult(status -> {
            lock(source + ":transport:" + key);
            if (jdbc.queryForObject("SELECT count(*) FROM failure_observation WHERE source=? AND transport_key=?", Long.class, source, key) > 0) { return; }
            UUID eventId = event == null ? null : ReportDecoder.uuid(event.get("eventId"));
            UUID incident = event == null ? null : incident(source, eventId, handler, event);
            jdbc.update("""
                    INSERT INTO failure_observation(id,source,incident_id,event_id,handler_id,kind,body,body_sha256,
                    transport_key,event_conflict) VALUES (?,?,?,?,?,?,?, ?,?,?)
                    """, UUID.randomUUID(), source, incident, eventId, handler,
                    event == null ? "QUARANTINED" : "RAW_DLX", event == null ? null : decoder.canonical(event),
                    ReportDecoder.hash(bytes), key, event != null && eventConflict(source, eventId, event));
        });
    }
    private UUID incident(String source, UUID eventId, String handler, JsonNode event) {
        lock(source + ":event:" + eventId);
        String body = decoder.canonical(event);
        jdbc.update("INSERT INTO incident_event(source,event_id,body,body_sha256) VALUES (?,?,?,?) ON CONFLICT DO NOTHING", source,eventId,body,hash(body));
        jdbc.update("INSERT INTO incident(id,source,event_id,handler_id) VALUES (?,?,?,?) ON CONFLICT DO NOTHING", UUID.randomUUID(),source,eventId,handler);
        return jdbc.queryForObject("SELECT id FROM incident WHERE source=? AND event_id=? AND handler_id=?", UUID.class,source,eventId,handler);
    }
    private boolean eventConflict(String source, UUID id, JsonNode event) {
        return !decoder.canonical(event).equals(jdbc.queryForObject("SELECT body FROM incident_event WHERE source=? AND event_id=?",String.class,source,id));
    }
    private void lock(String key) { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", key); }
    public static String hash(String text) { return ReportDecoder.hash(text.getBytes(StandardCharsets.UTF_8)); }
}
