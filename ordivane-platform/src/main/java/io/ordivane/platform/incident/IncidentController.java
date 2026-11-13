package io.ordivane.platform.incident;

import io.ordivane.platform.access.AccessPolicy;
import io.ordivane.platform.evidence.EvidenceService;
import io.ordivane.platform.shared.TraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 人类会话资源；来源Bearer不可进入，写操作同时受角色与会话CSRF约束。 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {
    private final AccessPolicy policy;
    private final IncidentService incidents;
    private final EvidenceService evidence;
    public IncidentController(AccessPolicy policy, IncidentService incidents, EvidenceService evidence) {
        this.policy=policy;this.incidents=incidents;this.evidence=evidence;
    }
    @GetMapping
    public Map<String,Object> page(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String source,@RequestParam(required=false) String status) {
        policy.requireHuman(); return incidents.page(page,size,source,status);
    }
    @GetMapping("/{id}")
    public Map<String,Object> detail(@PathVariable UUID id) { policy.requireHuman();return incidents.detail(id); }
    @GetMapping("/{id}/evidence")
    public List<Map<String,Object>> evidence(@PathVariable UUID id) { policy.requireHuman();return incidents.evidence(id); }
    @PostMapping("/{id}/evidence-collections")
    public Map<String,Object> collect(@PathVariable UUID id,HttpServletRequest request) throws java.io.IOException {
        var human=policy.requireOperator();
        // 本操作没有客户端可指定的URL、主体或证据引用；非空正文拒绝，防止静默忽略跨案参数。
        if(request.getInputStream().read()!=-1) { throw io.ordivane.platform.ingestion.IngestionException.invalid(); }
        return evidence.collect(id,human.id(),TraceFilter.traceId(request));
    }
}
