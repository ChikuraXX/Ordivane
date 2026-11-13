package io.ordivane.platform.integration.membership;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 成功必须同时具有当前业务事实及正确执行归属，有限空历史不是失败证明。 */
class MembershipResultVerifierTests {
    private final JsonMapper json=JsonMapper.builder().build();
    /** 最终严格核验器重放真实双应用观测，证明收紧字段检查不改变已验证合法路径。 */
    @Test void realObservationsRetainTheirVerdicts() throws Exception {
        var samples=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/execution/actual-observation-samples.json")));
        int count=0;var verifier=new MembershipResultVerifier();
        for(var sample:samples) {
            var id=sample.path("executionId");var result=verifier.verify(sample.path("event"),id.isNull()?null:UUID.fromString(id.asString()),sample.path("observation"));
            assertThat(result.verified()).as(sample.path("run").asString()).isEqualTo(sample.path("expectedVerified").asBoolean());
            assertThat(result.reason()).isEqualTo(sample.path("expectedReason").asString());count++;
        }
        assertThat(count).isGreaterThan(0);
    }
    @Test void requiresCurrentFactsAndExecutionOwnership() {
        var event=json.readTree("{\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"payload\":{\"orderId\":\"o1\",\"entitlementType\":\"MEMBERSHIP\"}}");
        UUID execution=UUID.randomUUID();
        var observation=json.readTree("""
            {"available":true,"order":{"order_id":"o1","entitlement_type":"MEMBERSHIP","status":"PAID"},
            "entitlement":{"orderId":"o1","entitlementType":"MEMBERSHIP","orderStatus":"PAID","active":true,"granted":true,
            "entitlements":[{"order_id":"o1","entitlement_type":"MEMBERSHIP","event_id":"00000000-0000-0000-0000-000000000001"}]},
            "processingRecords":[],"historyComplete":false,"processingRecordLimit":100}
            """);
        var verifier=new MembershipResultVerifier();
        assertThat(verifier.verify(event,execution,observation).verified()).isFalse();
        var records=((tools.jackson.databind.node.ObjectNode)observation).putArray("processingRecords");
        var record=records.addObject();record.put("event_id",event.path("eventId").asString());record.put("order_id","o1");
        record.put("entitlement_type","MEMBERSHIP");record.put("execution_id",execution.toString());record.put("result","GRANTED");
        record.put("attempt_id",UUID.randomUUID().toString());record.put("origin","RECOVERY");record.put("processed_at","2026-10-22T00:00:00Z");
        assertThat(verifier.verify(event,execution,observation).verified()).isTrue();
        record.put("origin","ORIGINAL");
        assertThat(verifier.verify(event,execution,observation).verified()).isFalse();
        record.put("origin","RECOVERY");
        assertThat(verifier.verify(event,UUID.randomUUID(),observation).verified()).isFalse();
        record.put("event_id",UUID.randomUUID().toString());
        assertThat(verifier.verify(event,execution,observation).reason()).isEqualTo("PROCESSING_OWNERSHIP_MISMATCH");
        record.put("event_id",event.path("eventId").asString());
        record.put("result","REFUND_REJECTED");
        assertThat(verifier.verify(event,execution,observation).verified()).isFalse();
        record.put("result","GRANTED");
        ((tools.jackson.databind.node.ObjectNode)observation.path("entitlement").path("entitlements").get(0)).put("event_id",UUID.randomUUID().toString());
        assertThat(verifier.verify(event,execution,observation).reason()).isEqualTo("ENTITLEMENT_OWNERSHIP_MISMATCH");
        ((tools.jackson.databind.node.ObjectNode)observation.path("entitlement").path("entitlements").get(0)).put("event_id",event.path("eventId").asString());
        ((tools.jackson.databind.node.ObjectNode)observation.path("entitlement")).put("active",false);
        assertThat(verifier.verify(event,execution,observation).verified()).isFalse();
        ((tools.jackson.databind.node.ObjectNode)observation.path("entitlement")).put("active","true");
        assertThat(verifier.verify(event,execution,observation).reason()).isEqualTo("CONTRADICTORY_FIELDS");
    }
    @Test void verifyOnlyUsesOriginalEventWithoutInventingOwnSendRecord() {
        var event=json.readTree("{\"eventId\":\"e\",\"payload\":{\"orderId\":\"o\",\"entitlementType\":\"MEMBERSHIP\"}}");
        var observation=json.readTree("""
            {"available":true,"order":{"order_id":"o","entitlement_type":"MEMBERSHIP","status":"PAID"},
            "entitlement":{"orderId":"o","entitlementType":"MEMBERSHIP","orderStatus":"PAID","active":true,"granted":true,
            "entitlements":[{"order_id":"o","entitlement_type":"MEMBERSHIP","event_id":"e"}]},
            "processingRecords":[{"attempt_id":"00000000-0000-0000-0000-000000000001","processed_at":"2026-10-22T00:00:00Z","origin":"ORIGINAL","event_id":"e","order_id":"o","entitlement_type":"MEMBERSHIP","execution_id":null,"result":"GRANTED"}]}
            """);
        assertThat(new MembershipResultVerifier().verify(event,null,observation).verified()).isTrue();
    }
}
