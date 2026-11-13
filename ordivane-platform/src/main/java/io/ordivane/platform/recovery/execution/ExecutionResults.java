package io.ordivane.platform.recovery.execution;

import io.ordivane.platform.configuration.ConfigurationLoader;
import io.ordivane.platform.incident.IncidentService;
import io.ordivane.platform.integration.membership.MembershipClient;
import io.ordivane.platform.integration.membership.MembershipResultVerifier;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 持久到期查证：不调用发布器，不依赖已过期的发送授权；保留有界不确定性。 */
@Service
public class ExecutionResults {
    private final JdbcTemplate jdbc;
    private final ExecutionStateStore state;
    private final MembershipClient membership;
    private final MembershipResultVerifier verifier;
    private final IncidentService incidents;
    private final JsonMapper json=JsonMapper.builder().build();
    private final int interval,deadline,limit;
    public ExecutionResults(JdbcTemplate jdbc,ExecutionStateStore state,MembershipClient membership,MembershipResultVerifier verifier,IncidentService incidents,
            @Value("${ordivane.execution.result-interval-seconds:10}") int interval,
            @Value("${ordivane.execution.result-deadline-seconds:300}") int deadline,
            @Value("${ordivane.execution.result-max-attempts:6}") int limit) {
        if(interval<1 || interval>300 || deadline<2 || deadline>86400 || limit<1 || limit>20) throw new IllegalArgumentException("查证预算无效");
        this.jdbc=jdbc;this.state=state;this.membership=membership;this.verifier=verifier;this.incidents=incidents;this.interval=interval;this.deadline=deadline;this.limit=limit;
    }
    /** 创建时固定预算及到期点；WAIT的等待期不占用查证期限。 */
    public void schedule(UUID id,int delay) {
        jdbc.update("UPDATE recovery_execution SET verification_limit=?,next_check_at=clock_timestamp()+(? * interval '1 second'),verification_deadline=clock_timestamp()+((?+?) * interval '1 second') WHERE id=?",limit,delay,delay,deadline,id);
    }
    /** 崩溃领取计入预算；只接管只读查证，绝不自动重放外部业务。 */
    public record Lease(UUID id,UUID owner,long fence) { }
    public Optional<Lease> claim() {
        UUID owner=UUID.randomUUID();
        var rows=jdbc.queryForList("""
            WITH claimed AS (UPDATE recovery_execution SET result_owner=?,result_fence=result_fence+1,result_lease_until=clock_timestamp()+interval '15 seconds',verification_count=verification_count+1
            WHERE id=(SELECT e.id FROM recovery_execution e WHERE e.next_check_at<=clock_timestamp()
            AND e.verification_count<e.verification_limit AND e.status NOT IN ('SUCCEEDED','CANCELLED','MANUAL_REQUIRED')
            AND (e.result_lease_until IS NULL OR e.result_lease_until<clock_timestamp())
            AND (e.action<>'REPLAY_ORIGINAL' OR EXISTS(SELECT 1 FROM execution_outbox o WHERE o.execution_id=e.id AND o.state IN ('STARTED','DONE')))
            ORDER BY e.next_check_at,e.id FOR UPDATE OF e SKIP LOCKED LIMIT 1) RETURNING id,result_owner,result_fence,result_lease_until)
            INSERT INTO execution_result_attempt(id,execution_id,fencing_token,claimed_at,lease_until)
            SELECT result_owner,id,result_fence,clock_timestamp(),result_lease_until FROM claimed RETURNING execution_id AS id,fencing_token AS result_fence
            """,owner);
        return rows.isEmpty()?Optional.empty():Optional.of(new Lease((UUID)rows.getFirst().get("id"),owner,(Long)rows.getFirst().get("result_fence")));
    }
    /** 仅查原事件/本执行业务证据；网络失败观测照常持久，不按空历史释放。 */
    public void verify(Lease lease) {
        var row=jdbc.queryForMap("SELECT e.action,v.body FROM recovery_execution e JOIN recovery_plan p ON p.id=e.plan_id JOIN incident i ON i.id=p.incident_id JOIN incident_event v ON v.source=i.source AND v.event_id=i.event_id WHERE e.id=?",lease.id());
        var event=json.readTree((String)row.get("body"));UUID scope="REPLAY_ORIGINAL".equals(row.get("action"))?lease.id():null;
        var observation=membership.observe(event,scope);var verdict=verifier.verify(event,scope,observation);
        state.locked(lease.id(),e->{
            if(!lease.owner().equals(e.get("result_owner")) || lease.fence()!=(Long)e.get("result_fence")
                    || Boolean.TRUE.equals(e.get("business_verified")) || Set.of("CANCELLED","MANUAL_REQUIRED").contains(e.get("status"))) return false;
            if(!jdbc.queryForObject("SELECT result_lease_until>clock_timestamp() FROM recovery_execution WHERE id=?",Boolean.class,lease.id())) return false;
            UUID evidence=UUID.randomUUID();String content=json.writeValueAsString(observation);
            jdbc.update("INSERT INTO execution_result_observation(id,execution_id,fencing_token,verified,reason,content,content_sha256) VALUES (?,?,?,?,?,?,?)",evidence,lease.id(),lease.fence(),verdict.verified(),verdict.reason(),content,ConfigurationLoader.sha256(content));
            boolean safe=verdict.verified();
            // 仍活动的发送者可能尚未调用网络；等其完成或租约失效，才能使发送令牌无效并释放。
            if(safe && "REPLAY_ORIGINAL".equals(e.get("action"))) safe=jdbc.queryForObject("SELECT count(*)=0 FROM execution_outbox WHERE execution_id=? AND state='STARTED' AND lease_until>clock_timestamp()",Boolean.class,lease.id());
            if(safe) {
                jdbc.update("UPDATE execution_outbox SET fencing_token=fencing_token+1,owner=NULL,lease_until=NULL WHERE execution_id=?",lease.id());
                jdbc.update("UPDATE recovery_execution SET status='SUCCEEDED',business_verified=true,action_completed=true,reason='BUSINESS_CONFIRMED',next_check_at=NULL,result_owner=NULL,result_lease_until=NULL WHERE id=?",lease.id());
                incidents.resolveVerifiedExecution(lease.id(),evidence);
                state.release(lease.id(),"BUSINESS_CONFIRMED",evidence);
                state.record(lease.id(),"BUSINESS_CONFIRMED",Map.of("observationId",evidence));
            } else {
                boolean exhausted=jdbc.queryForObject("SELECT verification_count>=verification_limit OR verification_deadline<=clock_timestamp() FROM recovery_execution WHERE id=?",Boolean.class,lease.id());
                String reason=safe?verdict.reason():(verdict.verified()?"SENDER_LEASE_ACTIVE":verdict.reason());
                jdbc.update("UPDATE recovery_execution SET status='UNKNOWN',reason=?,action_completed=?,next_check_at=CASE WHEN ? THEN NULL ELSE clock_timestamp()+(? * interval '1 second') END,result_owner=NULL,result_lease_until=NULL WHERE id=?",reason,exhausted,exhausted,interval,lease.id());
                state.record(lease.id(),exhausted?"VERIFICATION_STOPPED":"VERIFICATION_PENDING",Map.of("reason",reason,"observationId",evidence,"budgetExhausted",exhausted));
            }
            return true;
        });
    }
    /** 最后一次领取崩溃也停止调度；保留资格和UNKNOWN，不伪造业务失败。 */
    public void expireAbandoned() {
        for(UUID id:jdbc.queryForList("SELECT id FROM recovery_execution WHERE next_check_at IS NOT NULL AND verification_count>=verification_limit AND result_lease_until<clock_timestamp()",UUID.class))
            state.locked(id,e->{
                if(jdbc.update("UPDATE recovery_execution SET status='UNKNOWN',reason='VERIFICATION_BUDGET_EXHAUSTED',action_completed=true,next_check_at=NULL,result_owner=NULL,result_lease_until=NULL WHERE id=? AND next_check_at IS NOT NULL AND verification_count>=verification_limit AND result_lease_until<clock_timestamp() AND NOT business_verified",id)==1)
                    state.record(id,"VERIFICATION_STOPPED",Map.of("reason","VERIFICATION_BUDGET_EXHAUSTED"));return null;
            });
    }
}
