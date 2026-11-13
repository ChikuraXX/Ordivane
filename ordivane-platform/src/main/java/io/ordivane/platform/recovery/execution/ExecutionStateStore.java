package io.ordivane.platform.recovery.execution;

import io.ordivane.platform.configuration.ConfigurationLoader;
import io.ordivane.platform.ingestion.IngestionException;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 生命周期统一锁序：案件→业务键→执行→Outbox；HTTP始终位于该短事务外。 */
@Component
public class ExecutionStateStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json=JsonMapper.builder().build();
    public ExecutionStateStore(JdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc;tx=new TransactionTemplate(manager); }
    /** 先读不可变归属，再按同一顺序锁；调用者不得预先持Outbox锁。 */
    public <T> T locked(UUID id,Function<Map<String,Object>,T> work) {
        var rows=jdbc.queryForList("SELECT e.business_key,p.incident_id FROM recovery_execution e JOIN recovery_plan p ON p.id=e.plan_id WHERE e.id=?",id);
        if(rows.isEmpty()) throw new IngestionException(404,"NOT_FOUND","执行不存在");var ref=rows.getFirst();
        return tx.execute(status->{
            jdbc.queryForList("SELECT id FROM incident WHERE id=? FOR UPDATE",ref.get("incident_id"));
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,1))",ref.get("business_key"));
            var e=jdbc.queryForMap("SELECT * FROM recovery_execution WHERE id=? FOR UPDATE",id);
            jdbc.queryForList("SELECT execution_id FROM execution_outbox WHERE execution_id=? FOR UPDATE",id);
            return work.apply(e);
        });
    }
    /** 追加不可变机器时间线，原始执行和尝试记录不删除。 */
    public void record(UUID id,String kind,Map<String,?> detail) {
        jdbc.update("INSERT INTO execution_timeline(id,execution_id,kind,document) VALUES (?,?,?,?)",UUID.randomUUID(),id,kind,json.writeValueAsString(detail));
    }
    /** 仅删除当前持有者，后继执行不能被迟到Worker释放。调用方须已持统一锁。 */
    public void release(UUID id,String reason,UUID observation) {
        int changed=jdbc.update("DELETE FROM execution_eligibility WHERE execution_id=? AND business_key=(SELECT business_key FROM recovery_execution WHERE id=?)",id,id);
        if(changed==1) {
            jdbc.update("UPDATE recovery_execution SET released_at=clock_timestamp() WHERE id=?",id);
            record(id,"ELIGIBILITY_RELEASED",Map.of("reason",reason,"observationId",observation==null?"":observation.toString()));
        }
    }
}
