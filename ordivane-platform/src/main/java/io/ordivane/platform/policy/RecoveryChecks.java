package io.ordivane.platform.policy;

import io.ordivane.platform.configuration.ConfigurationLoader;
import io.ordivane.platform.ingestion.IngestionException;
import io.ordivane.platform.ingestion.ReportDecoder;
import io.ordivane.platform.integration.membership.MembershipClient;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 只读业务复检，远程HTTP在短事务外执行；不查未来尚不存在的执行表。 */
@Component
public class RecoveryChecks {
    private final ConfigurationLoader.Loaded configuration;
    private final ReportDecoder decoder;
    private final MembershipClient membership;
    public RecoveryChecks(ConfigurationLoader.Loaded configuration,ReportDecoder decoder,MembershipClient membership) {
        this.configuration=configuration;this.decoder=decoder;this.membership=membership;
    }
    /** 快照可能大于请求限额，使用独立JSON解析，不把HTTP正文上限套在配置上。 */
    private JsonNode snapshot() { return tools.jackson.databind.json.JsonMapper.builder().build().readTree(configuration.snapshot()); }
    /** 精确场景及动作检查返回TTL秒数，不使用客户端期限。 */
    public int ttl(Map<String,Object> incident,JsonNode event,String action) {
        String id=configuration.match((String)incident.get("source"),event.path("eventType").asString(""),
                event.path("schemaVersion").asInt(-1),(String)incident.get("handler_id"))
                .orElseThrow(()->new IngestionException(409,"CONFLICT","未匹配已登记场景"));
        JsonNode recovery=snapshot().path("scenarios").path(id).path("scenario").path("recovery");
        boolean allowed=false;for(JsonNode a:recovery.path("allowed_actions")) allowed |= action.equals(a.asString());
        if(!allowed) throw new IngestionException(409,"CONFLICT","配置不允许该动作");
        return recovery.path("plan_ttl_seconds").intValue();
    }
    /** 全动作检查严格事件和场景；仅重放要求PAID且无任何已授权益。 */
    public Map<String,Object> collect(Map<String,Object> incident,JsonNode plan) {
        List<String> reasons=new ArrayList<>();
        JsonNode event=tools.jackson.databind.json.JsonMapper.builder().build().readTree((String)incident.get("original_event"));
        try { decoder.event(event); } catch(IngestionException invalid) { reasons.add("EVENT_SCHEMA_INVALID"); }
        if(!reasons.isEmpty()) return result(reasons,Map.of(),false);
        try { ttl(incident,event,plan.path("action").asString()); }
        catch(IngestionException invalid) { reasons.add("SCENARIO_OR_ACTION_UNREGISTERED"); }
        if(!reasons.isEmpty()) return result(reasons,Map.of(),false);
        Map<String,Object> facts=membership.collect(event,null);
        JsonNode data=tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(facts);
        if(plan.path("action").asString().equals("REPLAY_ORIGINAL")) {
            boolean registered=false;
            for(JsonNode target:snapshot().at("/configuration/registry/replay_targets")) registered |= target.path("id").asString().equals("membership-replay");
            if(!registered) reasons.add("TARGET_UNREGISTERED");
            if(!data.at("/order/status").asString().equals("PAID")) reasons.add("ORDER_NOT_PAID_OR_REFUNDED");
            if(data.at("/entitlement/granted").asBoolean() || data.at("/entitlement/active").asBoolean()
                    || !data.at("/entitlement/entitlements").isEmpty()) reasons.add("ENTITLEMENT_EXISTS");
        }
        return result(reasons,facts,Boolean.TRUE.equals(facts.get("businessVerified")));
    }
    /** 显式声明未授权以及后继必须补齐的检查，不用不存在的数据伪造通过。 */
    private Map<String,Object> result(List<String> reasons,Map<String,Object> facts,boolean verified) {
        return Map.of("passed",reasons.isEmpty(),"reasons",reasons,"facts",facts,"businessVerified",verified,
                "executionAuthorized",false,"deferredChecks",List.of("CONFLICTING_EXECUTION","ATOMIC_EXECUTION_ELIGIBILITY"));
    }
}
