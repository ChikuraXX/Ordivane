package io.ordivane.platform.recovery.plan;

import io.ordivane.platform.access.AccessPolicy;
import io.ordivane.platform.configuration.ConfigurationLoader;
import io.ordivane.platform.incident.IncidentService;
import io.ordivane.platform.ingestion.IngestionException;
import io.ordivane.platform.ingestion.ReportDecoder;
import io.ordivane.platform.policy.RecoveryChecks;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 不可变计划和追加式审批；同案件行锁串行化版本/检查/决定，HTTP不占数据库锁。 */
@Service
public class RecoveryPlanService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AccessPolicy access;
    private final IncidentService incidents;
    private final ConfigurationLoader.Loaded configuration;
    private final ReportDecoder decoder;
    private final RecoveryChecks checks;
    private final JsonMapper json=JsonMapper.builder().build();
    public RecoveryPlanService(JdbcTemplate jdbc,PlatformTransactionManager transactions,AccessPolicy access,
            IncidentService incidents,ConfigurationLoader.Loaded configuration,ReportDecoder decoder,RecoveryChecks checks) {
        this.jdbc=jdbc;tx=new TransactionTemplate(transactions);this.access=access;this.incidents=incidents;
        this.configuration=configuration;this.decoder=decoder;this.checks=checks;
    }
    /** 创建新版本，前版精确比较与插入同一事务；不覆盖历史审批。 */
    public JsonNode create(UUID incidentId,JsonNode input) {
        var human=access.requireOperator();PlanInput.create(input);
        return tx.execute(status->{
            lock(incidentId);identity(human.id(),"OPERATOR");
            Map<String,Object> incident=incidents.detail(incidentId);
            JsonNode event=json.readTree((String)incident.get("original_event"));
            decoder.event(event);
            var prior=jdbc.queryForList("SELECT id FROM recovery_plan WHERE incident_id=? ORDER BY version DESC LIMIT 1",UUID.class,incidentId);
            JsonNode previous=input.get("previous");int version=1;
            if(prior.isEmpty()) { if(!previous.isNull()) throw conflict("首版不能引用旧版本"); }
            else {
                if(previous.isNull() || !prior.getFirst().equals(ReportDecoder.uuid(previous.get("id")))) throw conflict("必须引用同案最新版本");
                JsonNode old=load(prior.getFirst());exact(old,previous);version=Math.addExact(old.get("version").intValue(),1);
            }
            for(JsonNode evidence:input.get("evidenceIds")) {
                UUID evidenceId=ReportDecoder.uuid(evidence);incidents.requireEvidence(incidentId,evidenceId);
                if(!"COLLECTED".equals(jdbc.queryForObject("SELECT outcome FROM evidence_snapshot WHERE id=?",String.class,evidenceId))) throw conflict("引用证据未成功采集");
            }
            Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
            Instant expires=now.plusSeconds(checks.ttl(incident,event,input.get("action").asString()));UUID id=UUID.randomUUID();
            ObjectNode p=json.createObjectNode();p.put("id",id.toString());p.put("incidentId",incidentId.toString());p.put("version",version);
            p.set("previousId",previous.isNull()?json.nullNode():previous.get("id"));p.put("requesterId",human.id().toString());
            p.put("configurationDigest",configuration.digest());p.put("eventDigest",(String)incident.get("event_sha256"));
            p.set("action",input.get("action"));p.set("parameters",input.get("parameters"));p.set("evidenceIds",input.get("evidenceIds"));
            p.put("createdAt",now.toString());p.put("expiresAt",expires.toString());
            p.put("actionDigest",ConfigurationLoader.sha256(decoder.canonical(json.valueToTree(Map.of("action",input.get("action"),"parameters",input.get("parameters"))))));
            String document=decoder.canonical(p),digest=ConfigurationLoader.sha256(document);
            jdbc.update("INSERT INTO recovery_plan(id,incident_id,version,previous_id,requester_id,configuration_digest,event_digest,action_digest,digest,document,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
                    id,incidentId,version,previous.isNull()?null:ReportDecoder.uuid(previous.get("id")),human.id(),configuration.digest(),p.get("eventDigest").asString(),p.get("actionDigest").asString(),digest,document,now.toString(),expires.toString());
            return view(id);
        });
    }
    /** 人类可查看不可变历史及当前派生状态；不把旧批准冒称仍有效。 */
    public JsonNode detail(UUID id) { access.requireHuman();return view(id); }
    /** 预检查事实在事务外收集，提交时再次核对版本和配置；依赖失败也保存结果。 */
    public JsonNode precheck(UUID id,JsonNode input) {
        var human=access.requireOperator();ReportDecoder.fields(input,"version","digest");PlanInput.exact(input);
        JsonNode plan=load(id);current(plan,input);requireDraft(id);
        Instant start=Instant.now().truncatedTo(ChronoUnit.MICROS);Map<String,Object> result;boolean unavailable=false;
        try { result=checks.collect(incidents.detail(uuid(plan,"incidentId")),plan); }
        catch(IngestionException e) {
            if(e.status()!=503) throw e;
            unavailable=true;result=Map.of("passed",false,"reasons",List.of("DEPENDENCY_UNAVAILABLE"),"facts",Map.of(),"businessVerified",false,"executionAuthorized",false,
                    "deferredChecks",List.of("CONFLICTING_EXECUTION","ATOMIC_EXECUTION_ELIGIBILITY"));
        }
        ObjectNode record=json.valueToTree(result);UUID checkId=UUID.randomUUID();
        Instant expiry=start.plusSeconds(60);Instant planExpiry=Instant.parse(plan.get("expiresAt").asString());if(planExpiry.isBefore(expiry)) expiry=planExpiry;
        record.put("id",checkId.toString());record.put("planId",id.toString());record.set("version",plan.get("version"));record.set("digest",plan.get("digest"));
        record.set("configurationDigest",plan.get("configurationDigest"));record.put("createdAt",start.toString());record.put("expiresAt",expiry.toString());
        tx.executeWithoutResult(status->{lock(uuid(plan,"incidentId"));identity(human.id(),"OPERATOR");current(load(id),input);requireDraft(id);
            jdbc.update("INSERT INTO recovery_precheck(id,plan_id,document,passed,created_at,expires_at) VALUES (?,?,?,?,?::timestamptz,?::timestamptz)",checkId,id,decoder.canonical(record),record.get("passed").asBoolean(),record.get("createdAt").asString(),record.get("expiresAt").asString());});
        if(unavailable) throw new IngestionException(503,"UNAVAILABLE","事实依赖失败，检查已保留");
        return record;
    }
    /** 申请人必须为本版创建者；不允许他人替换持久化申请主体。 */
    public JsonNode requestApproval(UUID id,JsonNode input) {
        var human=access.requireOperator();ReportDecoder.fields(input,"version","digest","precheckId");PlanInput.exact(input);UUID checkId=ReportDecoder.uuid(input.get("precheckId"));
        return mutate(id,()->{
            identity(human.id(),"OPERATOR");JsonNode plan=load(id);current(plan,input);requireDraft(id);
            if(!human.id().equals(uuid(plan,"requesterId"))) throw new org.springframework.security.access.AccessDeniedException("只有申请人可以提交");
            var latest=jdbc.queryForList("SELECT id FROM recovery_precheck WHERE plan_id=? ORDER BY created_at DESC,id DESC LIMIT 1",UUID.class,id);
            if(latest.isEmpty() || !latest.getFirst().equals(checkId)) throw conflict("需要最新预检查");
            validCheck(id,checkId);
            jdbc.update("INSERT INTO recovery_approval_request(plan_id,precheck_id) VALUES (?,?)",id,checkId);return view(id);
        });
    }
    /** 同案件锁及主键双重保证仅一个决定；自审核对数据库保存的申请人。 */
    public JsonNode decide(UUID id,JsonNode input) {
        ReportDecoder.fields(input,"version","digest","decision");PlanInput.exact(input);
        String decision=ReportDecoder.text(input.get("decision"));ReportDecoder.check(Set.of("APPROVE","REJECT").contains(decision));
        var human=access.requireOtherApprover(uuid(load(id),"requesterId"));
        return mutate(id,()->{
            identity(human.id(),"APPROVER");JsonNode plan=load(id);access.requireOtherApprover(uuid(plan,"requesterId"));current(plan,input);
            var request=jdbc.queryForList("SELECT precheck_id FROM recovery_approval_request WHERE plan_id=?",UUID.class,id);
            if(request.isEmpty() || decided(id)) throw conflict("计划不处于待审批状态");validCheck(id,request.getFirst());
            jdbc.update("INSERT INTO recovery_approval_decision(plan_id,approver_id,decision,digest) VALUES (?,?,?,?)",id,human.id(),decision,plan.get("digest").asString());return view(id);
        });
    }
    /** BE-060内部入口：重新鉴权、准确版本、批准人有效性及最新HTTP事实；返回值不是原子执行资格。 */
    public JsonNode recheckBeforeExecution(UUID id,int version,String digest) {
        return recheckFor(access.requireOperator().id(),id,version,digest);
    }
    /** Worker主体只能来自已鉴权创建的持久执行，不创建SecurityContext或接收外传申请人。 */
    public JsonNode recheckPersistedExecution(UUID executionId) {
        var e=persistedExecution(executionId);
        return recheckFor((UUID)e.get("requester_id"),(UUID)e.get("plan_id"),(Integer)e.get("plan_version"),(String)e.get("plan_digest"));
    }
    /** HTTP事实在事务外收集；前后均重新核验持久身份和批准。 */
    private JsonNode recheckFor(UUID requester,UUID id,int version,String digest) {
        JsonNode input=json.valueToTree(Map.of("version",version,"digest",digest));PlanInput.exact(input);
        JsonNode plan=mutate(id,()->{identity(requester,"OPERATOR");JsonNode p=load(id);approved(p,input);identity(uuid(p,"requesterId"),"OPERATOR");return p;});
        Map<String,Object> facts=checks.collect(incidents.detail(uuid(plan,"incidentId")),plan);
        return mutate(id,()->{identity(requester,"OPERATOR");JsonNode p=load(id);approved(p,input);identity(uuid(p,"requesterId"),"OPERATOR");ObjectNode result=json.valueToTree(facts);
            result.put("planId",id.toString());result.put("version",version);result.put("digest",digest);return result;});
    }
    /** 创建者来自当前会话；同一短事务持案件/身份锁直到资格、执行、待发意图提交。 */
    public <T> T withExecutionEligibility(UUID id,int version,String digest,Supplier<T> work) {
        return finalExecutionCheck(access.requireOperator().id(),id,version,digest,work);
    }
    /** 实际Outbox发送前从执行记录恢复主体，并在最终事务内复核。 */
    public <T> T withPersistedExecutionEligibility(UUID executionId,Supplier<T> work) {
        var e=persistedExecution(executionId);
        return finalExecutionCheck((UUID)e.get("requester_id"),(UUID)e.get("plan_id"),(Integer)e.get("plan_version"),(String)e.get("plan_digest"),work);
    }
    /** 不执行HTTP；调用者必须先取得最新事实，并在此回调内验证资格和租约。 */
    private <T> T finalExecutionCheck(UUID requester,UUID id,int version,String digest,Supplier<T> work) {
        return mutate(id,()->{identity(requester,"OPERATOR");JsonNode p=load(id);
            approved(p,json.valueToTree(Map.of("version",version,"digest",digest)));identity(uuid(p,"requesterId"),"OPERATOR");return work.get();});
    }
    /** 执行申请人不可由Worker参数替代；不存在即拒绝。 */
    private Map<String,Object> persistedExecution(UUID id) {
        var rows=jdbc.queryForList("SELECT requester_id,plan_id,plan_version,plan_digest FROM recovery_execution WHERE id=?",id);
        if(rows.isEmpty()) throw new IngestionException(404,"NOT_FOUND","执行不存在");return rows.getFirst();
    }
    /** 校验历史批准仍对应准确版本，审批账号当前仍有权限。 */
    private void approved(JsonNode p,JsonNode input) {
        current(p,input);UUID id=uuid(p,"id");
        var decisions=jdbc.queryForList("SELECT approver_id,digest FROM recovery_approval_decision WHERE plan_id=? AND decision='APPROVE'",id);
        if(decisions.isEmpty() || !p.get("digest").asString().equals(decisions.getFirst().get("digest"))) throw conflict("需要准确版本的有效批准");
        identity((UUID)decisions.getFirst().get("approver_id"),"APPROVER");
    }
    /** 统一短事务写边界；每次重新读持久状态。 */
    private <T> T mutate(UUID id,Supplier<T> work) {
        UUID incident=uuid(load(id),"incidentId");return tx.execute(status->{lock(incident);return work.get();});
    }
    /** 锁案件而非某版，使新版本与旧版审批不能竞态提交。 */
    private void lock(UUID incident) {
        if(jdbc.queryForList("SELECT id FROM incident WHERE id=? FOR UPDATE",UUID.class,incident).isEmpty()) throw new IngestionException(404,"NOT_FOUND","案件不存在");
    }
    /** 数据库身份再验证并持共享行锁至提交，避免会话检查后撤销的窗口。 */
    private void identity(UUID id,String role) {
        var rows=jdbc.queryForList("SELECT role,enabled FROM access_user WHERE id=? FOR SHARE",id);
        if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled")) || !role.equals(rows.getFirst().get("role"))) throw new org.springframework.security.access.AccessDeniedException("当前权限已撤销");
    }
    /** 从不可变数据库document恢复版本，不接受客户端摘要替代服务端绑定。 */
    private JsonNode load(UUID id) {
        var rows=jdbc.queryForList("SELECT document,digest FROM recovery_plan WHERE id=?",id);
        if(rows.isEmpty()) throw new IngestionException(404,"NOT_FOUND","计划不存在");
        ObjectNode p=(ObjectNode)json.readTree((String)rows.getFirst().get("document"));p.put("digest",(String)rows.getFirst().get("digest"));return p;
    }
    /** 历史状态不改写；失效优先于批准展示。 */
    private JsonNode view(UUID id) {
        ObjectNode p=(ObjectNode)load(id);String state="DRAFT";
        if(jdbc.queryForObject("SELECT count(*) FROM recovery_approval_request WHERE plan_id=?",Integer.class,id)>0) state="PENDING_APPROVAL";
        var decisions=jdbc.queryForList("SELECT approver_id,decision,digest,created_at FROM recovery_approval_decision WHERE plan_id=?",id);
        // 两类审批历史均从各自持久行显式转为 UTC 字符串，避免 Timestamp 日期序列化截断 PG 微秒。
        for(var row:decisions) row.put("created_at",((java.sql.Timestamp)row.get("created_at")).toInstant().toString());
        if(!decisions.isEmpty()) state="APPROVE".equals(decisions.getFirst().get("decision"))?"APPROVED":"REJECTED";
        p.put("recordedStatus",state);String invalid=invalidReason(p);p.put("status",invalid==null?state:invalid);
        p.put("executionAuthorized",false);p.set("decisions",json.valueToTree(decisions));
        var requests=jdbc.queryForList("SELECT precheck_id,created_at FROM recovery_approval_request WHERE plan_id=?",id);
        for(var row:requests) row.put("created_at",((java.sql.Timestamp)row.get("created_at")).toInstant().toString());
        p.set("approvalRequests",json.valueToTree(requests));
        var records=json.createArrayNode();for(String text:jdbc.queryForList("SELECT document FROM recovery_precheck WHERE plan_id=? ORDER BY created_at DESC,id DESC",String.class,id)) records.add(json.readTree(text));p.set("prechecks",records);return p;
    }
    /** 全部变更入口共同的时效/配置/谱系检查。 */
    private void current(JsonNode p,JsonNode input) { exact(p,input);if(invalidReason(p)!=null) throw conflict("计划已失效，请创建新版本"); }
    /** 优先标记已被替代，再检查当前配置与绝对UTC截止时刻。 */
    private String invalidReason(JsonNode p) {
        int latest=jdbc.queryForObject("SELECT max(version) FROM recovery_plan WHERE incident_id=?",Integer.class,uuid(p,"incidentId"));
        if(latest!=p.get("version").intValue()) return "SUPERSEDED";
        if(!configuration.digest().equals(p.get("configurationDigest").asString())) return "CONFIGURATION_CHANGED";
        if(!Instant.now().isBefore(Instant.parse(p.get("expiresAt").asString()))) return "EXPIRED";
        return null;
    }
    /** 摘要来自服务器不可变版本，不能只比较版本序号。 */
    private void exact(JsonNode p,JsonNode input) { if(p.get("version").intValue()!=PlanInput.positive(input.get("version"),Integer.MAX_VALUE) || !p.get("digest").asString().equals(input.get("digest").asString())) throw conflict("计划版本或摘要冲突"); }
    /** 只能在持案件锁的决定事务内用此结果判定可写。 */
    private boolean decided(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM recovery_approval_decision WHERE plan_id=?",Integer.class,id)>0; }
    /** 已申请的版本被冻结；后续修改必须创建新版。 */
    private void requireDraft(UUID id) { if(jdbc.queryForObject("SELECT count(*) FROM recovery_approval_request WHERE plan_id=?",Integer.class,id)>0) throw conflict("已提交的计划不可重新预检查或申请"); }
    /** 预检查归属、通过与时限由数据库当前时刻再次核验。 */
    private void validCheck(UUID plan,UUID check) {
        if(jdbc.queryForObject("SELECT count(*) FROM recovery_precheck WHERE id=? AND plan_id=? AND passed AND expires_at>clock_timestamp()",Integer.class,check,plan)!=1) throw conflict("预检查未通过或已过期");
    }
    /** 内部已验证或持久化文档中的UUID，不处理未验证HTTP输入。 */
    private UUID uuid(JsonNode p,String field) { return UUID.fromString(p.get(field).asString()); }
    /** 复用既有业务错误翻译与trace，消息只传服务器固定文案。 */
    private IngestionException conflict(String message) { return new IngestionException(409,"CONFLICT",message); }
}
