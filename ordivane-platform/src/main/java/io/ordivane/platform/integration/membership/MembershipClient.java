package io.ordivane.platform.integration.membership;

import io.ordivane.platform.ingestion.IngestionException;
import io.ordivane.platform.ingestion.ReportDecoder;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 固定可信HTTP服务查询；不跨库，不接收报告给出的URL或凭据。 */
@Component
public class MembershipClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;
    private final String token;
    private final ReportDecoder decoder;
    public MembershipClient(@Value("${ordivane.membership.base-url:http://127.0.0.1:8081}") String url,
            @Value("${ordivane.membership.token:}") String token, ReportDecoder decoder) {
        base=URI.create(url);this.token=token;this.decoder=decoder;
        if(base.getHost()==null || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || !(base.getScheme().equals("https") || (base.getScheme().equals("http")
                    && List.of("127.0.0.1","localhost","[::1]").contains(base.getHost())))) {
            throw new IllegalArgumentException("演示HTTP配置无效");
        }
    }
    /** 三份事实全部校验关联；返回快照不代表自动解决案件。 */
    public Map<String,Object> collect(JsonNode event,UUID executionId) {
        String orderId=event.get("payload").get("orderId").asString();
        String eventId=event.get("eventId").asString();
        JsonNode order=get("/api/v1/orders/"+orderId);
        JsonNode entitlement=get("/api/v1/orders/"+orderId+"/entitlements/MEMBERSHIP");
        JsonNode records=get("/api/v1/processing-records?eventId="+eventId+(executionId==null?"":"&executionId="+executionId));
        try {
            ReportDecoder.check(orderId.equals(ReportDecoder.text(order.get("order_id")))
                    && "MEMBERSHIP".equals(ReportDecoder.text(order.get("entitlement_type")))
                    && orderId.equals(ReportDecoder.text(entitlement.get("orderId")))
                    && "MEMBERSHIP".equals(ReportDecoder.text(entitlement.get("entitlementType")))
                    && entitlement.get("active").isBoolean() && entitlement.get("granted").isBoolean()
                    && order.get("status").isString() && order.get("status").equals(entitlement.get("orderStatus"))
                    && records.isArray() && records.size()<=100 && entitlement.get("entitlements").isArray());
            boolean linkedActive=false;
            for(JsonNode e:entitlement.get("entitlements")) {
                ReportDecoder.check(orderId.equals(ReportDecoder.text(e.get("order_id")))
                        && "MEMBERSHIP".equals(ReportDecoder.text(e.get("entitlement_type"))));
                ReportDecoder.uuid(e.get("event_id"));
                linkedActive |= eventId.equals(e.get("event_id").asString());
            }
            boolean processed=false;
            for(JsonNode r:records) {
                ReportDecoder.check(eventId.equals(ReportDecoder.text(r.get("event_id")))
                        && orderId.equals(ReportDecoder.text(r.get("order_id")))
                        && "MEMBERSHIP".equals(ReportDecoder.text(r.get("entitlement_type"))));
                ReportDecoder.uuid(r.get("attempt_id"));
                if(!r.get("execution_id").isNull()) { ReportDecoder.uuid(r.get("execution_id")); }
                ReportDecoder.check(executionId==null || executionId.toString().equals(ReportDecoder.text(r.get("execution_id"))));
                String result=ReportDecoder.text(r.get("result"));
                ReportDecoder.check(List.of("GRANTED","ALREADY_GRANTED","REFUND_REJECTED").contains(result));
                processed |= List.of("GRANTED","ALREADY_GRANTED").contains(result);
            }
            boolean verified="PAID".equals(order.get("status").asString()) && entitlement.get("active").asBoolean()
                    && linkedActive && processed;
            var facts=new java.util.LinkedHashMap<String,Object>();
            facts.putAll(Map.of("order",order,"entitlement",entitlement,"processingRecords",records,
                    "processingRecordLimit",100,"historyComplete",false,"businessVerified",verified));
            facts.put("executionId",executionId);
            return facts;
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    private JsonNode get(String path) {
        if(token.isBlank()) { throw unavailable(); }
        var future=http.sendAsync(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(2))
                .header("Authorization","Bearer "+token).GET().build(), info -> new BoundedBody());
        try {
            var result=future.get(2,TimeUnit.SECONDS);
            if(result.statusCode()!=200 || result.body().length>ReportDecoder.MAX_BYTES) { throw unavailable(); }
            return decoder.parse(result.body());
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw unavailable(); }
        catch(Exception failure) { throw unavailable(); }
        finally { if(!future.isDone()) { future.cancel(true); } }
    }
    /** 保留三次有界查询的原始正文、范围及各自时点；部分失败也保留成功观测。 */
    public JsonNode observe(JsonNode event,UUID executionId) {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var result=json.createObjectNode();
        String order=event.at("/payload/orderId").asString(),eventId=event.path("eventId").asString();
        result.put("startedAt",java.time.Instant.now().toString());result.put("eventId",eventId);
        if(executionId==null) result.putNull("executionId");else result.put("executionId",executionId.toString());
        result.put("historyComplete",false);result.put("processingRecordLimit",100);result.put("available",true);
        String[][] queries={{"order","/api/v1/orders/"+order},{"entitlement","/api/v1/orders/"+order+"/entitlements/MEMBERSHIP"},
            {"processingRecords","/api/v1/processing-records?eventId="+eventId+(executionId==null?"":"&executionId="+executionId)}};
        var scope=result.putArray("queries");
        for(String[] query:queries) {
            var q=scope.addObject();q.put("path",query[1]);q.put("startedAt",java.time.Instant.now().toString());
            try { result.set(query[0],get(query[1]));q.put("outcome","HTTP_200"); }
            catch(IngestionException unavailable) { result.put("available",false);q.put("outcome","UNAVAILABLE"); }
            q.put("finishedAt",java.time.Instant.now().toString());
        }
        result.put("finishedAt",java.time.Instant.now().toString());return result;
    }
    private IngestionException unavailable() { return new IngestionException(503,"UNAVAILABLE","演示事实查询未确认"); }
    @jakarta.annotation.PreDestroy public void close() { http.shutdownNow(); }
    /** 响应正文上限32768字节，超限立即取消，慢正文仍受完整Future预算限制。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription=value;value.request(1); }
        public void onNext(List<java.nio.ByteBuffer> chunks) {
            for(var chunk:chunks) {
                byte[] part=new byte[Math.min(chunk.remaining(),ReportDecoder.MAX_BYTES+1-bytes.size())];chunk.get(part);bytes.writeBytes(part);
                if(bytes.size()>ReportDecoder.MAX_BYTES) { subscription.cancel();result.complete(bytes.toByteArray());return; }
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
