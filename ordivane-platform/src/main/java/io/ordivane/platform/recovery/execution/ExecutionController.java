package io.ordivane.platform.recovery.execution;

import io.ordivane.platform.access.AccessPolicy;
import io.ordivane.platform.audit.AuditService;
import io.ordivane.platform.ingestion.ReportDecoder;
import io.ordivane.platform.shared.TraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** 创建持久恢复执行及查询真实投递状态；复用会话、CSRF与审计边界。 */
@RestController
@RequestMapping("/api/v1")
public class ExecutionController {
    private final ExecutionService executions;
    private final ReportDecoder decoder;
    private final AccessPolicy access;
    private final AuditService audit;
    public ExecutionController(ExecutionService executions,ReportDecoder decoder,AccessPolicy access,AuditService audit) {
        this.executions=executions;this.decoder=decoder;this.access=access;this.audit=audit;
    }
    /** 同步202仅表示持久提交；幂等重取也202，不以broker确认冒充成功。 */
    @PostMapping("/recovery-plans/{id}/executions")
    public ResponseEntity<JsonNode> create(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request) throws IOException {
        var human=access.requireOperator();var subject=new AuditService.Subject(AuditService.SubjectType.HUMAN,human.id().toString());
        JsonNode input=decoder.parse(request.getInputStream().readNBytes(ReportDecoder.MAX_BYTES+1));JsonNode result;
        try { result=executions.create(id,key,input); }
        catch(AccessDeniedException denied) { throw denied; }
        catch(RuntimeException failure) { audit.record(subject,"EXECUTION_CREATE","RECOVERY_PLAN",id.toString(),AuditService.Result.FAILURE,TraceFilter.traceId(request));throw failure; }
        audit.record(subject,"EXECUTION_CREATE","EXECUTION",result.get("id").asString(),AuditService.Result.SUCCESS,TraceFilter.traceId(request));
        return ResponseEntity.accepted().body(result);
    }
    /** 查询不修改资格，不进行BE-061业务结果判定。 */
    @GetMapping("/executions/{id}")
    public JsonNode detail(@PathVariable UUID id) { return executions.detail(id); }
    /** 200实际取消或重复请求；202只受理已开始执行的请求，副作用不可撤回。 */
    @PostMapping("/executions/{id}/cancellations")
    public ResponseEntity<JsonNode> cancel(@PathVariable UUID id,HttpServletRequest request) throws IOException {
        var human=access.requireOperator();
        if(request.getInputStream().read()!=-1) throw io.ordivane.platform.ingestion.IngestionException.invalid();
        var subject=new AuditService.Subject(AuditService.SubjectType.HUMAN,human.id().toString());JsonNode result;
        try { result=executions.cancel(id); }
        catch(AccessDeniedException denied) { throw denied; }
        catch(RuntimeException failure) { audit.record(subject,"EXECUTION_CANCEL","EXECUTION",id.toString(),AuditService.Result.FAILURE,TraceFilter.traceId(request));throw failure; }
        audit.record(subject,"EXECUTION_CANCEL","EXECUTION",id.toString(),AuditService.Result.SUCCESS,TraceFilter.traceId(request));
        return ResponseEntity.status(result.path("cancelledAt").isNull()?202:200).body(result);
    }
}
