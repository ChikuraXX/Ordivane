package io.ordivane.platform.recovery.plan;

import io.ordivane.platform.access.AccessPolicy;
import io.ordivane.platform.audit.AuditService;
import io.ordivane.platform.ingestion.ReportDecoder;
import io.ordivane.platform.shared.TraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** 五个人类API；业务提交后写成功审计，审计独立事务且失败不吞。 */
@RestController
@RequestMapping("/api/v1")
public class RecoveryPlanController {
    private final RecoveryPlanService plans;
    private final ReportDecoder decoder;
    private final AccessPolicy access;
    private final AuditService audit;
    public RecoveryPlanController(RecoveryPlanService plans,ReportDecoder decoder,AccessPolicy access,AuditService audit) {
        this.plans=plans;this.decoder=decoder;this.access=access;this.audit=audit;
    }
    /** 新版201，不发布任何恢复消息。 */
    @PostMapping("/incidents/{id}/recovery-plans")
    public ResponseEntity<JsonNode> create(@PathVariable UUID id,HttpServletRequest request) throws IOException {
        access.requireOperator();JsonNode input=body(request);
        return ResponseEntity.status(201).body(audited("PLAN_CREATE",id,request,()->plans.create(id,input)));
    }
    /** 历史计划和当前有效状态。 */
    @GetMapping("/recovery-plans/{id}")
    public JsonNode detail(@PathVariable UUID id) { return plans.detail(id); }
    /** 同步只读预检查。 */
    @PostMapping("/recovery-plans/{id}/prechecks")
    public JsonNode precheck(@PathVariable UUID id,HttpServletRequest request) throws IOException {
        access.requireOperator();JsonNode input=body(request);return audited("PLAN_PRECHECK",id,request,()->plans.precheck(id,input));
    }
    /** 提交准确版本的审批申请。 */
    @PostMapping("/recovery-plans/{id}/approval-requests")
    public JsonNode requestApproval(@PathVariable UUID id,HttpServletRequest request) throws IOException {
        access.requireOperator();JsonNode input=body(request);return audited("PLAN_SUBMIT",id,request,()->plans.requestApproval(id,input));
    }
    /** 他人准确版本决定；版本自审检查由服务读取数据库执行。 */
    @PostMapping("/recovery-plans/{id}/approval-decisions")
    public JsonNode decide(@PathVariable UUID id,HttpServletRequest request) throws IOException {
        access.requireHuman();JsonNode input=body(request);return audited("PLAN_DECIDE",id,request,()->plans.decide(id,input));
    }
    /** 有界读取后复用严格UTF-8/重复键/尾随输入检查。 */
    private JsonNode body(HttpServletRequest request) throws IOException { return decoder.parse(request.getInputStream().readNBytes(ReportDecoder.MAX_BYTES+1)); }
    /** 安全拒绝交原有安全边界唯一记录；其他失败保留一次结构化审计。 */
    private JsonNode audited(String action,UUID id,HttpServletRequest request,Supplier<JsonNode> operation) {
        var human=access.requireHuman();var subject=new AuditService.Subject(AuditService.SubjectType.HUMAN,human.id().toString());
        // 创建入口的路径ID是案件；其余入口的路径ID是计划版本，不能混记对象种类。
        String objectType=action.equals("PLAN_CREATE")?"INCIDENT":"RECOVERY_PLAN";
        JsonNode result;
        try { result=operation.get(); }
        catch(AccessDeniedException denied) { throw denied; }
        catch(RuntimeException failure) {
            audit.record(subject,action,objectType,id.toString(),AuditService.Result.FAILURE,TraceFilter.traceId(request));throw failure;
        }
        audit.record(subject,action,objectType,id.toString(),AuditService.Result.SUCCESS,TraceFilter.traceId(request));return result;
    }
}
