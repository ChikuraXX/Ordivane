package io.ordivane.platform.incident;

import io.ordivane.platform.ingestion.IngestionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 案件只读投影；分页顺序固定，不伪造后继执行或诊断。 */
@Service
public class IncidentService {
    private final JdbcTemplate jdbc;
    public IncidentService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Map<String,Object> page(int page, int size, String source, String status) {
        if (page<0 || page>100000 || size<1 || size>100 || (source!=null && !source.matches("[A-Za-z0-9_.-]{1,128}"))
                || (status!=null && !List.of("OPEN","RESOLVED").contains(status))) { throw IngestionException.invalid(); }
        String where=" WHERE (?::text IS NULL OR source=?) AND (?::text IS NULL OR status=?)";
        return Map.of("items",jdbc.queryForList("SELECT * FROM incident"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",source,source,status,status,size,(long)page*size),
                "total",jdbc.queryForObject("SELECT count(*) FROM incident"+where,Long.class,source,source,status,status),"page",page,"size",size);
    }
    public Map<String,Object> detail(UUID id) {
        var rows=jdbc.queryForList("SELECT i.*,e.body AS original_event,e.body_sha256 AS event_sha256 FROM incident i JOIN incident_event e USING(source,event_id) WHERE i.id=?",id);
        if(rows.isEmpty()) { throw new IngestionException(404,"NOT_FOUND","案件不存在"); }
        var result=rows.getFirst();
        result.put("observations",jdbc.queryForList("SELECT id,report_id,observation_id,kind,body,body_sha256,event_conflict,received_at FROM failure_observation WHERE incident_id=? ORDER BY received_at,id",id));
        return result;
    }
    public List<Map<String,Object>> evidence(UUID id) {
        detail(id);
        return jdbc.queryForList("SELECT * FROM evidence_snapshot WHERE incident_id=? ORDER BY collected_at DESC,id DESC",id);
    }
    /** 证据引用须属于目标案件；后继能力也复用此归属检查。 */
    public void requireEvidence(UUID incident, UUID evidence) {
        if(jdbc.queryForObject("SELECT count(*) FROM evidence_snapshot WHERE incident_id=? AND id=?",Long.class,incident,evidence)!=1) {
            throw new IngestionException(400,"INVALID_REQUEST","证据不属于当前案件");
        }
    }
    /** 调用方持案件及执行锁；仅当前准确计划的充分观测可在同事务解决，旧版不覆盖。 */
    public void resolveVerifiedExecution(UUID execution,UUID observation) {
        jdbc.update("""
            UPDATE incident i SET status='RESOLVED',resolved_at=clock_timestamp(),resolved_execution_id=?,resolution_observation_id=?
            FROM recovery_execution e,recovery_plan p,execution_result_observation o
            WHERE e.id=? AND p.id=e.plan_id AND i.id=p.incident_id AND o.id=? AND o.execution_id=e.id AND o.verified
            AND e.business_verified AND i.status='OPEN' AND p.version=(SELECT max(n.version) FROM recovery_plan n WHERE n.incident_id=i.id)
            """,execution,observation,execution,observation);
    }
}
