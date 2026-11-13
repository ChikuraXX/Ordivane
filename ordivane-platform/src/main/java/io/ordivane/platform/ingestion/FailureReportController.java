package io.ordivane.platform.ingestion;

import io.ordivane.platform.access.AccessPolicy;
import io.ordivane.platform.audit.AuditService;
import io.ordivane.platform.shared.TraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立来源链接收报告；认证声明匹配先于业务写入。 */
@RestController
public class FailureReportController {
    private final ReportDecoder decoder;
    private final AccessPolicy policy;
    private final IngestionStore store;
    private final AuditService audit;
    public FailureReportController(ReportDecoder decoder, AccessPolicy policy, IngestionStore store, AuditService audit) {
        this.decoder=decoder; this.policy=policy; this.store=store; this.audit=audit;
    }
    @PostMapping(value="/api/v1/failure-reports", consumes="application/json")
    public ResponseEntity<Map<String, UUID>> accept(HttpServletRequest request) throws IOException {
        var report = decoder.report(request.getInputStream().readNBytes(ReportDecoder.MAX_BYTES + 1));
        var source = policy.requireSource(report.source());
        var subject = new AuditService.Subject(AuditService.SubjectType.SOURCE, source.sourceId());
        boolean created;
        try { created = store.report(report); }
        catch (RuntimeException failure) {
            audit.record(subject,"REPORT_ACCEPT","FAILURE_REPORT",report.reportId().toString(),AuditService.Result.FAILURE,TraceFilter.traceId(request));
            throw failure;
        }
        audit.record(subject,"REPORT_ACCEPT","FAILURE_REPORT",report.reportId().toString(),AuditService.Result.SUCCESS,TraceFilter.traceId(request));
        return ResponseEntity.status(created ? 202 : 200).body(Map.of("reportId",report.reportId()));
    }
}
