package io.ordivane.platform.recovery.plan;

import io.ordivane.platform.access.AccessPolicy;
import io.ordivane.platform.configuration.ConfigurationLoader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 从公开详情入口覆盖真实 view 及 JSON 边界，避免 Timestamp 日期转换截断 PG 微秒。 */
class RecoveryPlanTimeProjectionTests {
    /** 非零尾数、整毫秒/整秒、秒边界及纪元前时刻均按瞬时值精确比较，不使用容差。 */
    @ParameterizedTest
    @ValueSource(strings={"2026-10-26T06:57:51.229967Z","2026-10-26T06:57:51.257001Z",
            "2026-10-26T06:57:51.123000Z","2026-10-26T06:57:51Z",
            "2026-10-26T06:57:51.999999Z","1969-12-31T23:59:59.999999Z"})
    void detailPreservesEachPersistentHistoryInstantAndAssociation(String literal) {
        var jdbc=mock(JdbcTemplate.class);var access=mock(AccessPolicy.class);
        var mapper=JsonMapper.builder().build();
        UUID plan=UUID.randomUUID(),incident=UUID.randomUUID(),precheck=UUID.randomUUID(),approver=UUID.randomUUID();
        String digest="a".repeat(64),configurationDigest="b".repeat(64);
        var document=mapper.createObjectNode();document.put("id",plan.toString());document.put("incidentId",incident.toString());
        document.put("version",1);document.put("configurationDigest",configurationDigest);document.put("expiresAt","2099-01-01T00:00:00Z");
        document.put("createdAt","2026-10-26T06:57:50.123456Z");
        when(jdbc.queryForList("SELECT document,digest FROM recovery_plan WHERE id=?",plan))
                .thenReturn(List.of(Map.of("document",mapper.writeValueAsString(document),"digest",digest)));
        when(jdbc.queryForObject("SELECT count(*) FROM recovery_approval_request WHERE plan_id=?",Integer.class,plan)).thenReturn(1);
        when(jdbc.queryForObject("SELECT max(version) FROM recovery_plan WHERE incident_id=?",Integer.class,incident)).thenReturn(1);
        Instant requestTime=Instant.parse(literal),decisionTime=requestTime.plusNanos(1000);
        when(jdbc.queryForList("SELECT approver_id,decision,digest,created_at FROM recovery_approval_decision WHERE plan_id=?",plan))
                .thenReturn(new ArrayList<>(List.of(new HashMap<>(Map.of("approver_id",approver,"decision","APPROVE","digest",digest,"created_at",Timestamp.from(decisionTime))))));
        when(jdbc.queryForList("SELECT precheck_id,created_at FROM recovery_approval_request WHERE plan_id=?",plan))
                .thenReturn(new ArrayList<>(List.of(new HashMap<>(Map.of("precheck_id",precheck,"created_at",Timestamp.from(requestTime))))));
        var configuration=new ConfigurationLoader.Loaded(configurationDigest,"{}",List.of(),Map.of(),"membership-demo");
        var service=new RecoveryPlanService(jdbc,mock(PlatformTransactionManager.class),access,null,configuration,null,null);
        var response=mapper.readTree(mapper.writeValueAsString(service.detail(plan)));
        var request=response.get("approvalRequests").get(0);var decision=response.get("decisions").get(0);
        assertThat(request.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("precheck_id","created_at");
        assertThat(decision.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("approver_id","decision","digest","created_at");
        assertThat(request.get("precheck_id").asString()).isEqualTo(precheck.toString());
        assertThat(decision.get("approver_id").asString()).isEqualTo(approver.toString());
        assertThat(decision.get("decision").asString()).isEqualTo("APPROVE");
        assertThat(decision.get("digest").asString()).isEqualTo(digest);
        assertThat(request.get("created_at").asString()).endsWith("Z");
        assertThat(Instant.parse(request.get("created_at").asString())).isEqualTo(requestTime);
        assertThat(Instant.parse(decision.get("created_at").asString())).isEqualTo(decisionTime);
        assertThat(response.get("createdAt").asString()).isEqualTo("2026-10-26T06:57:50.123456Z");
        assertThat(response.get("status").asString()).isEqualTo("APPROVED");
        assertThat(response.get("executionAuthorized").asBoolean()).isFalse();
        verify(access).requireHuman();
    }
}
