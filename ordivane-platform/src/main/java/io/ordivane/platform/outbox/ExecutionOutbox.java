package io.ordivane.platform.outbox;

import io.ordivane.platform.recovery.execution.ExecutionService;
import io.ordivane.platform.recovery.execution.ExecutionTransport;
import io.ordivane.platform.recovery.execution.ExecutionStateStore;
import io.ordivane.platform.recovery.plan.RecoveryPlanService;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久租约、单调fencing与发送意图；只接管尚未开始外部发送的任务。 */
@Repository
public class ExecutionOutbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RecoveryPlanService plans;
    private final ExecutionTransport transport;
    private final int leaseSeconds;
    private final ExecutionStateStore lifecycle;
    public ExecutionOutbox(JdbcTemplate jdbc,PlatformTransactionManager manager,RecoveryPlanService plans,ExecutionTransport transport,
            @Value("${ordivane.execution.lease-seconds:30}") int leaseSeconds,ExecutionStateStore lifecycle) {
        if(leaseSeconds<2 || leaseSeconds>300) throw new IllegalArgumentException("执行租约须为2..300秒");
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.plans=plans;this.transport=transport;this.leaseSeconds=leaseSeconds;this.lifecycle=lifecycle;
    }
    /** 领取凭据仅服务器生成，过期令牌不能开始发布或回写。 */
    public record Lease(UUID executionId,UUID owner,long fencingToken) { }
    /** 已持久化UNKNOWN的单次发送资料，正文和目标只能来自数据库。 */
    public record Dispatch(Lease lease,UUID attemptId,String body,String exchange,String routingKey) { }
    /** 单条SQL通过SKIP LOCKED和条件更新原子领取；租约以PG时钟为准。 */
    public Optional<Lease> claim() {
        UUID owner=UUID.randomUUID();
        var rows=jdbc.queryForList("UPDATE execution_outbox SET owner=?,fencing_token=fencing_token+1,lease_until=clock_timestamp()+(? * interval '1 second') WHERE execution_id=(SELECT execution_id FROM execution_outbox WHERE state='PENDING' AND (lease_until IS NULL OR lease_until<clock_timestamp()) ORDER BY created_at,execution_id FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING execution_id,fencing_token",owner,leaseSeconds);
        return rows.isEmpty()?Optional.empty():Optional.of(new Lease((UUID)rows.getFirst().get("execution_id"),owner,(Long)rows.getFirst().get("fencing_token")));
    }
    /** HTTP不占数据库事务；最后事务内重新核身份/版本/批准/期限、资格、映射和未过期租约。 */
    public Optional<Dispatch> begin(Lease lease) {
        ExecutionService.requireFacts(plans.recheckPersistedExecution(lease.executionId()));
        return plans.withPersistedExecutionEligibility(lease.executionId(),()->lifecycle.locked(lease.executionId(),execution->{
            var rows=jdbc.queryForList("SELECT o.*,e.event_id FROM execution_outbox o JOIN recovery_execution e ON e.id=o.execution_id JOIN execution_eligibility q ON q.execution_id=e.id AND q.business_key=e.business_key WHERE o.execution_id=? AND o.state='PENDING' AND o.owner=? AND o.fencing_token=? AND o.lease_until>clock_timestamp() FOR UPDATE OF o",lease.executionId(),lease.owner(),lease.fencingToken());
            if(rows.isEmpty()) return Optional.empty();var row=rows.getFirst();
            if(!transport.target().fingerprint().equals(row.get("transport_fingerprint"))) {
                block(lease);return Optional.empty();
            }
            UUID attempt=UUID.randomUUID();
            jdbc.update("UPDATE execution_outbox SET state='STARTED' WHERE execution_id=?",lease.executionId());
            jdbc.update("UPDATE recovery_execution SET status='UNKNOWN' WHERE id=?",lease.executionId());
            jdbc.update("INSERT INTO execution_attempt(id,execution_id,fencing_token,outcome) VALUES (?,?,?,'UNKNOWN')",attempt,lease.executionId(),lease.fencingToken());
            lifecycle.record(lease.executionId(),"SEND_STARTED",Map.of("attemptId",attempt,"fence",lease.fencingToken()));
            return Optional.of(new Dispatch(lease,attempt,(String)row.get("body"),(String)row.get("exchange_name"),(String)row.get("routing_key")));
        }));
    }
    /** 发送前拒绝保留资格；CAS避免旧持有者覆盖后继决定。 */
    public boolean block(Lease lease) {
        return lifecycle.locked(lease.executionId(),execution->{
            int changed=jdbc.update("UPDATE execution_outbox SET state='BLOCKED' WHERE execution_id=? AND owner=? AND fencing_token=? AND state='PENDING' AND lease_until>clock_timestamp()",lease.executionId(),lease.owner(),lease.fencingToken());
            if(changed==1) {
                jdbc.update("UPDATE recovery_execution SET status='BLOCKED',reason='DISPATCH_RECHECK_REJECTED' WHERE id=?",lease.executionId());
                lifecycle.record(lease.executionId(),"DISPATCH_BLOCKED",Map.of("fence",lease.fencingToken()));
            }return changed==1;
        });
    }
    /** 只允许活动租约的持有者落确认；即使ACK，状态仍未核验。 */
    public boolean finish(Dispatch dispatch,String outcome) {
        if(!Set.of("ACK","RETURNED","NACK","CONFIRM_TIMEOUT","CONNECTION_LOST").contains(outcome)) throw new IllegalArgumentException("无效发送结果");
        Lease lease=dispatch.lease();
        return lifecycle.locked(lease.executionId(),execution->{
            int changed=jdbc.update("UPDATE execution_outbox SET state='DONE' WHERE execution_id=? AND owner=? AND fencing_token=? AND state='STARTED' AND lease_until>clock_timestamp()",lease.executionId(),lease.owner(),lease.fencingToken());
            if(changed!=1) return false;
            String state=switch(outcome) {case "ACK"->"DELIVERED_UNVERIFIED";case "RETURNED"->"RETURNED";case "NACK"->"NACK";default->"UNKNOWN";};
            jdbc.update("UPDATE execution_attempt SET outcome=?,finished_at=clock_timestamp() WHERE id=? AND execution_id=? AND fencing_token=? AND outcome='UNKNOWN'",outcome,dispatch.attemptId(),lease.executionId(),lease.fencingToken());
            jdbc.update("UPDATE recovery_execution SET status=?,reason='DELIVERY_FINISHED_UNVERIFIED' WHERE id=? AND NOT business_verified AND cancelled_at IS NULL",state,lease.executionId());
            lifecycle.record(lease.executionId(),"DELIVERY_FINISHED",Map.of("outcome",outcome,"fence",lease.fencingToken()));return true;
        });
    }
}
