package io.ordivane.platform.recovery.execution;

import io.ordivane.platform.access.AccessPolicy;
import io.ordivane.platform.configuration.ConfigurationLoader;
import io.ordivane.platform.ingestion.IngestionException;
import io.ordivane.platform.ingestion.ReportDecoder;
import io.ordivane.platform.recovery.plan.PlanInput;
import io.ordivane.platform.recovery.plan.RecoveryPlanService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 同调用人请求幂等与跨计划业务资格；所有写入交给计划最终短事务统一提交。 */
@Service
public class ExecutionService {
    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final RecoveryPlanService plans;
    private final ReportDecoder decoder;
    private final ExecutionTransport transport;
    private final ExecutionStateStore state;
    private final ExecutionResults results;
    private final JsonMapper json=JsonMapper.builder().build();
    public ExecutionService(JdbcTemplate jdbc,AccessPolicy access,RecoveryPlanService plans,ReportDecoder decoder,ExecutionTransport transport,ExecutionStateStore state,ExecutionResults results) {
        this.jdbc=jdbc;this.access=access;this.plans=plans;this.decoder=decoder;this.transport=transport;this.state=state;this.results=results;
    }
    /** 同键重试先匹配完整指纹，不因旧事实失效而另建执行。 */
    public JsonNode create(UUID planId,String key,JsonNode input) {
        UUID requester=access.requireOperator().id();
        ReportDecoder.check(key!=null && key.matches("[A-Za-z0-9._:-]{1,128}"));
        ReportDecoder.fields(input,"version","digest");PlanInput.exact(input);
        var rows=jdbc.queryForList("SELECT document FROM recovery_plan WHERE id=?",String.class,planId);
        if(rows.isEmpty()) throw new IngestionException(404,"NOT_FOUND","计划不存在");
        JsonNode plan=json.readTree(rows.getFirst());
        String fingerprint=ConfigurationLoader.sha256(decoder.canonical(json.valueToTree(Map.of(
                "requesterId",requester,"planId",planId,"version",input.get("version").decimalValue().intValueExact(),"digest",input.get("digest").asString(),
                "action",plan.get("action"),"parameters",plan.get("parameters"),"configurationDigest",plan.get("configurationDigest"),"transportFingerprint",transport.target().fingerprint()))));
        JsonNode prior=existing(requester,key,fingerprint);if(prior!=null) return prior;
        String action=plan.get("action").asString();boolean replay="REPLAY_ORIGINAL".equals(action);
        int version=input.get("version").decimalValue().intValueExact();String digest=input.get("digest").asString();
        requireFacts(plans.recheckBeforeExecution(planId,version,digest));
        return plans.withExecutionEligibility(planId,version,digest,()->{
            // 同调用人同键跨案件也串行；唯一约束是最后防线，锁冲突不转成500。
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",requester+":"+key);
            JsonNode same=existing(requester,key,fingerprint);if(same!=null) return same;
            var source=jdbc.queryForMap("SELECT i.source,i.handler_id,e.body FROM incident i JOIN incident_event e USING(source,event_id) WHERE i.id=?",UUID.fromString(plan.get("incidentId").asString()));
            JsonNode event=json.readTree((String)source.get("body"));decoder.event(event);
            String businessKey=ConfigurationLoader.sha256(decoder.canonical(json.valueToTree(Map.of("source",event.get("source"),"orderId",event.at("/payload/orderId"),
                    "entitlementType",event.at("/payload/entitlementType"),"handlerId",source.get("handler_id")))));
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,1))",businessKey);
            if(replay && jdbc.queryForObject("SELECT count(*) FROM execution_eligibility WHERE business_key=?",Integer.class,businessKey)!=0)
                throw new IngestionException(409,"CONFLICTING_EXECUTION","此业务资源已有保留执行资格");
            UUID id=UUID.randomUUID();var target=transport.target();
            jdbc.update("INSERT INTO recovery_execution(id,requester_id,idempotency_key,fingerprint,plan_id,plan_version,plan_digest,event_id,business_key,transport_fingerprint,status) VALUES (?,?,?,?,?,?,?,?,?,?,'QUEUED')",
                    id,requester,key,fingerprint,planId,version,digest,UUID.fromString(event.get("eventId").asString()),businessKey,target.fingerprint());
            if(replay) {
                jdbc.update("INSERT INTO execution_eligibility(business_key,execution_id,conflicting_execution,atomic_execution_eligibility) VALUES (?,?,'PASS','PASS')",businessKey,id);
                jdbc.update("INSERT INTO execution_outbox(execution_id,body,exchange_name,routing_key,queue_name,transport_fingerprint,state) VALUES (?,?,?,?,?,?,'PENDING')",
                        id,decoder.canonical(event),target.exchange(),target.routingKey(),target.queue(),target.fingerprint());
            }
            boolean manual="MANUAL_INTERVENTION".equals(action),wait="WAIT_AND_RECHECK".equals(action);
            jdbc.update("UPDATE recovery_execution SET action=?,status=?,action_completed=?,reason=? WHERE id=?",action,manual?"MANUAL_REQUIRED":wait?"WAITING":"QUEUED",manual,
                    manual?"MANUAL_INTERVENTION_REQUIRED":wait?"WAIT_UNTIL_DUE":replay?"AWAITING_DISPATCH":"AWAITING_VERIFICATION",id);
            if(!manual) results.schedule(id,wait?plan.at("/parameters/delaySeconds").intValue():0);
            state.record(id,"CREATED",Map.of("action",action,"planVersion",version,"planDigest",digest,"eligibilityHeld",replay));
            return view(id);
        });
    }
    /** 事实检查失败不创建资格；依赖503由既有边界传播。 */
    public static void requireFacts(JsonNode facts) {
        if(!facts.path("passed").asBoolean()) throw new IngestionException(409,"EXECUTION_CHECK_FAILED","当前事实不满足执行条件");
    }
    /** 同键同指纹返回原执行；映射或参数不同明确409。 */
    private JsonNode existing(UUID requester,String key,String fingerprint) {
        var rows=jdbc.queryForList("SELECT id,fingerprint FROM recovery_execution WHERE requester_id=? AND idempotency_key=?",requester,key);
        if(rows.isEmpty()) return null;
        if(!fingerprint.equals(rows.getFirst().get("fingerprint"))) throw new IngestionException(409,"IDEMPOTENCY_CONFLICT","幂等键已绑定其他执行请求");
        return view((UUID)rows.getFirst().get("id"));
    }
    /** 人类只读查询，不推断业务成功，也不触发验证/发送。 */
    public JsonNode detail(UUID id) { access.requireHuman();return state.locked(id,e->view(id)); }
    /** 同步受理取消；PENDING原子撤意图和旧租约，STARTED仅受理请求并继续只读查证。 */
    public JsonNode cancel(UUID id) {
        UUID requester=access.requireOperator().id();
        return state.locked(id,e->{
            var users=jdbc.queryForList("SELECT role,enabled FROM access_user WHERE id=? FOR SHARE",requester);
            if(users.isEmpty() || !Boolean.TRUE.equals(users.getFirst().get("enabled")) || !"OPERATOR".equals(users.getFirst().get("role")))
                throw new org.springframework.security.access.AccessDeniedException("当前权限已撤销");
            if(e.get("cancel_requested_at")!=null) return view(id);
            if(Boolean.TRUE.equals(e.get("action_completed"))) throw new IngestionException(409,"EXECUTION_TERMINAL","执行动作已经结束");
            jdbc.update("UPDATE recovery_execution SET cancel_requested_at=clock_timestamp() WHERE id=?",id);
            int pending=jdbc.update("UPDATE execution_outbox SET state='CANCELLED',owner=NULL,lease_until=NULL,fencing_token=fencing_token+1 WHERE execution_id=? AND state IN ('PENDING','BLOCKED')",id);
            boolean noSend=!"REPLAY_ORIGINAL".equals(e.get("action"));
            if(pending==1 || noSend) {
                jdbc.update("UPDATE recovery_execution SET status='CANCELLED',reason='CANCELLED_BEFORE_SEND',action_completed=true,cancelled_at=clock_timestamp(),next_check_at=NULL,result_owner=NULL,result_fence=result_fence+1,result_lease_until=NULL WHERE id=?",id);
                state.release(id,"CANCELLED_BEFORE_SEND",null);
                state.record(id,"CANCELLED",Map.of("requestedBy",requester,"externalEffectsWithdrawn",false));
            } else state.record(id,"CANCELLATION_REQUESTED",Map.of("requestedBy",requester,"reason","ALREADY_STARTED_OR_UNCERTAIN","externalEffectsWithdrawn",false));
            return view(id);
        });
    }
    /** 显式投影避免泄露内部原始幂等键、消息或数据库字段。 */
    private JsonNode view(UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM recovery_execution WHERE id=?",id);
        if(rows.isEmpty()) throw new IngestionException(404,"NOT_FOUND","执行不存在");var e=rows.getFirst();
        var result=json.createObjectNode();result.put("id",id.toString());result.put("planId",e.get("plan_id").toString());result.put("requesterId",e.get("requester_id").toString());
        result.put("version",(Integer)e.get("plan_version"));result.put("digest",(String)e.get("plan_digest"));result.put("eventId",e.get("event_id").toString());
        result.put("businessKey",(String)e.get("business_key"));result.put("fingerprint",(String)e.get("fingerprint"));result.put("transportFingerprint",(String)e.get("transport_fingerprint"));
        result.put("status",(String)e.get("status"));result.put("createdAt",((java.sql.Timestamp)e.get("created_at")).toInstant().toString());
        result.put("eligibilityHeld",jdbc.queryForObject("SELECT count(*)>0 FROM execution_eligibility WHERE execution_id=?",Boolean.class,id));result.put("businessVerified",(Boolean)e.get("business_verified"));
        result.put("action",(String)e.get("action"));result.put("actionCompleted",(Boolean)e.get("action_completed"));result.put("reason",(String)e.get("reason"));
        var delivery=jdbc.queryForList("SELECT state FROM execution_outbox WHERE execution_id=?",String.class,id);
        if(delivery.isEmpty()) result.putNull("deliveryState");else result.put("deliveryState",delivery.getFirst());
        result.put("incidentStatus",jdbc.queryForObject("SELECT i.status FROM incident i JOIN recovery_plan p ON p.incident_id=i.id WHERE p.id=?",String.class,e.get("plan_id")));
        result.put("verificationCount",(Integer)e.get("verification_count"));result.put("verificationLimit",(Integer)e.get("verification_limit"));
        for(String[] field:new String[][]{{"nextCheckAt","next_check_at"},{"verificationDeadline","verification_deadline"},{"cancelRequestedAt","cancel_requested_at"},{"cancelledAt","cancelled_at"},{"releasedAt","released_at"}}) {
            if(e.get(field[1])==null) result.putNull(field[0]);else result.put(field[0],((java.sql.Timestamp)e.get(field[1])).toInstant().toString());
        }
        boolean replay="REPLAY_ORIGINAL".equals(e.get("action"));
        result.set("checks",json.valueToTree(Map.of("CONFLICTING_EXECUTION",replay?"PASS":"NOT_APPLICABLE","ATOMIC_EXECUTION_ELIGIBILITY",replay?"PASS":"NOT_APPLICABLE")));
        var observations=json.createArrayNode();
        for(var o:jdbc.queryForList("SELECT * FROM execution_result_observation WHERE execution_id=? ORDER BY observed_at,id",id)) {
            var n=json.createObjectNode();n.put("id",o.get("id").toString());n.put("verified",(Boolean)o.get("verified"));n.put("reason",(String)o.get("reason"));
            n.put("observedAt",((java.sql.Timestamp)o.get("observed_at")).toInstant().toString());n.put("contentSha256",(String)o.get("content_sha256"));n.set("content",json.readTree((String)o.get("content")));observations.add(n);
        }
        result.set("observations",observations);var timeline=json.createArrayNode();
        var verifications=json.createArrayNode();
        for(var a:jdbc.queryForList("SELECT a.*,o.id AS observation_id FROM execution_result_attempt a LEFT JOIN execution_result_observation o ON o.execution_id=a.execution_id AND o.fencing_token=a.fencing_token WHERE a.execution_id=? ORDER BY a.claimed_at,a.id",id)) {
            var n=verifications.addObject();n.put("attemptId",a.get("id").toString());n.put("fencingToken",(Long)a.get("fencing_token"));
            n.put("claimedAt",((java.sql.Timestamp)a.get("claimed_at")).toInstant().toString());n.put("leaseUntil",((java.sql.Timestamp)a.get("lease_until")).toInstant().toString());
            if(a.get("observation_id")==null) n.putNull("observationId");else n.put("observationId",a.get("observation_id").toString());
        }
        result.set("verificationAttempts",verifications);
        for(var t:jdbc.queryForList("SELECT * FROM execution_timeline WHERE execution_id=? ORDER BY recorded_at,id",id)) {
            var n=timeline.addObject();n.put("kind",(String)t.get("kind"));n.put("recordedAt",((java.sql.Timestamp)t.get("recorded_at")).toInstant().toString());n.set("detail",json.readTree((String)t.get("document")));
        }
        result.set("timeline",timeline);
        var attempts=json.createArrayNode();
        for(var a:jdbc.queryForList("SELECT * FROM execution_attempt WHERE execution_id=? ORDER BY started_at,id",id)) {
            var n=json.createObjectNode();n.put("attemptId",a.get("id").toString());n.put("fencingToken",(Long)a.get("fencing_token"));n.put("outcome",(String)a.get("outcome"));
            n.put("startedAt",((java.sql.Timestamp)a.get("started_at")).toInstant().toString());
            if(a.get("finished_at")==null) n.putNull("finishedAt");else n.put("finishedAt",((java.sql.Timestamp)a.get("finished_at")).toInstant().toString());attempts.add(n);
        }
        result.set("attempts",attempts);return result;
    }
}
