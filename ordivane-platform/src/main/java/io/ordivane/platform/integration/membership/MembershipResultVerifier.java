package io.ordivane.platform.integration.membership;

import java.util.UUID;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 保守结果判定：有限历史仅能提供正向证据，不能证明从未执行。 */
@Component
public class MembershipResultVerifier {
    /** 判定原因与原始观测分别保存；false始终表示未确认，不隐含安全释放。 */
    public record Verdict(boolean verified,String reason) { }
    /** execution为空用于只读动作，按原事件事实核验；重放必须匹配本执行。 */
    public Verdict verify(JsonNode event,UUID execution,JsonNode observation) {
        if(!observation.path("available").asBoolean()) return new Verdict(false,"DEPENDENCY_UNAVAILABLE");
        String order=event.at("/payload/orderId").asString(),type=event.at("/payload/entitlementType").asString(),eventId=event.path("eventId").asString();
        JsonNode o=observation.path("order"),e=observation.path("entitlement"),records=observation.path("processingRecords");
        if(!same(o,"order_id",order) || !same(o,"entitlement_type",type) || !same(e,"orderId",order)
                || !same(e,"entitlementType",type) || !o.path("status").isString() || !o.path("status").equals(e.path("orderStatus"))
                || !e.path("granted").isBoolean() || !e.path("active").isBoolean() || !e.path("entitlements").isArray()
                || !records.isArray() || records.size()>100) return new Verdict(false,"CONTRADICTORY_FIELDS");
        boolean linked=false,processed=false,rejected=false;
        if(e.path("entitlements").size()>1) return new Verdict(false,"CONTRADICTORY_FIELDS");
        for(JsonNode row:e.path("entitlements")) {
            if(!same(row,"order_id",order) || !same(row,"entitlement_type",type) || !same(row,"event_id",eventId))
                return new Verdict(false,"ENTITLEMENT_OWNERSHIP_MISMATCH");
            linked=true;
        }
        if(e.path("granted").asBoolean()!=linked || (e.path("active").asBoolean() && !linked)) return new Verdict(false,"CONTRADICTORY_FIELDS");
        for(JsonNode row:records) {
            JsonNode owner=row.path("execution_id");
            if(!uuid(row.path("attempt_id")) || !(owner.isNull() || uuid(owner))
                    || !(owner.isNull()?same(row,"origin","ORIGINAL"):same(row,"origin","RECOVERY"))
                    || !timestamp(row.path("processed_at"))) return new Verdict(false,"CONTRADICTORY_PROCESSING_FIELDS");
            if(!same(row,"event_id",eventId) || !same(row,"order_id",order) || !same(row,"entitlement_type",type)
                    || (execution!=null && !same(row,"execution_id",execution.toString()))) return new Verdict(false,"PROCESSING_OWNERSHIP_MISMATCH");
            if(!row.path("result").isString() || !Set.of("GRANTED","ALREADY_GRANTED","REFUND_REJECTED").contains(row.path("result").asString()))
                return new Verdict(false,"CONTRADICTORY_FIELDS");
            rejected|=same(row,"result","REFUND_REJECTED");processed|=same(row,"result","GRANTED") || same(row,"result","ALREADY_GRANTED");
        }
        if(rejected || !same(o,"status","PAID") || !e.path("active").asBoolean()) return new Verdict(false,"CURRENT_BUSINESS_NOT_CONFIRMED");
        return linked && processed?new Verdict(true,"BUSINESS_CONFIRMED"):new Verdict(false,"INSUFFICIENT_FINITE_HISTORY");
    }
    /** 字段必须为真实字符串，不进行类型转换。 */
    private boolean same(JsonNode n,String field,String expected) { return n.path(field).isString() && expected.equals(n.path(field).asString()); }
    /** 严格UUID格式，不把缺失、对象或短格式视为真实处理身份。 */
    private boolean uuid(JsonNode n) {
        if(!n.isString()) return false;
        try { return UUID.fromString(n.asString()).toString().equals(n.asString()); } catch(IllegalArgumentException invalid) { return false; }
    }
    /** 原处理时点必须带时区；不据多次HTTP查询推断远端事务锁。 */
    private boolean timestamp(JsonNode n) {
        if(!n.isString()) return false;
        try { java.time.OffsetDateTime.parse(n.asString());return true; } catch(java.time.format.DateTimeParseException invalid) { return false; }
    }
}
