package io.ordivane.platform.recovery.plan;

import io.ordivane.platform.ingestion.ReportDecoder;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import static io.ordivane.platform.ingestion.ReportDecoder.*;

/** 计划请求严格边界：字段不省略、不强制转换、不接收原事件或主体。 */
public final class PlanInput {
    private PlanInput() { }
    /** 四动作是封闭业务集合，等待和转人工不转换为重放。 */
    public static final Set<String> ACTIONS=Set.of("REPLAY_ORIGINAL","VERIFY_ONLY","WAIT_AND_RECHECK","MANUAL_INTERVENTION");
    /** 返回精确正整数；拒绝字符串、布尔、近整数和溢出。 */
    public static int positive(JsonNode value,int max) {
        check(value!=null && value.isNumber());
        try { int n=value.decimalValue().intValueExact();check(n>0 && n<=max);return n; }
        catch(ArithmeticException e) { throw io.ordivane.platform.ingestion.IngestionException.invalid(); }
    }
    /** 核对版本和摘要类型；对象外层字段由调用操作独立校验。 */
    public static void exact(JsonNode value) {
        positive(value.get("version"),Integer.MAX_VALUE);
        check(text(value.get("digest")).matches("[a-f0-9]{64}"));
    }
    /** 新版本请求：首次previous显式null，其余必须带准确旧版本。 */
    public static void create(JsonNode value) {
        fields(value,"action","parameters","evidenceIds","previous");
        String action=text(value.get("action"));check(ACTIONS.contains(action));
        if(action.equals("WAIT_AND_RECHECK")) {
            fields(value.get("parameters"),"delaySeconds");positive(value.get("parameters").get("delaySeconds"),3600);
        } else fields(value.get("parameters"));
        JsonNode ids=value.get("evidenceIds");check(ids.isArray() && ids.size()>0 && ids.size()<=32);
        var unique=new HashSet<java.util.UUID>();for(JsonNode id:ids) check(unique.add(uuid(id)));
        if(!value.get("previous").isNull()) {
            fields(value.get("previous"),"id","version","digest");uuid(value.get("previous").get("id"));exact(value.get("previous"));
        }
    }
}
