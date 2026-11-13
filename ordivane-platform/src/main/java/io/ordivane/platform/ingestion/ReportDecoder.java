package io.ordivane.platform.ingestion;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 不做标量强制转换；显式检查冻结Schema、大小及跨字段关系。 */
@Component
public class ReportDecoder {
    public static final int MAX_BYTES = 32768;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.cfg.JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final java.util.Map<String, String> ERRORS = java.util.Map.of(
            "INVALID_MESSAGE", "消息格式或恢复头无效", "PROCESSING_FAILED", "会员处理未完成",
            "DOWNSTREAM_UNAVAILABLE", "模拟下游不可用", "COMMITTED_RESPONSE_TIMEOUT", "业务已提交但响应超时，需核验业务结果");
    /** 已严格验证的报告；规范正文保留null和类型，排序对象键。 */
    public record Report(UUID reportId, UUID observationId, String source, UUID eventId, String handlerId,
                         UUID executionId, JsonNode event, String canonical, JsonNode body) { }

    public JsonNode parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) { throw new IngestionException(413, "INVALID_REQUEST", "输入超过32768字节"); }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return json.readTree(text);
        } catch (Exception invalid) { throw IngestionException.invalid(); }
    }
    public Report report(byte[] bytes) {
        JsonNode n = parse(bytes);
        fields(n, "schemaVersion", "reportId", "observationId", "source", "eventId", "handlerId", "executionId",
                "origin", "observedAt", "errorCode", "errorSummary", "originalEvent", "bodySha256", "deadLetter");
        version(n.get("schemaVersion"));
        UUID report = uuid(n.get("reportId")), observation = uuid(n.get("observationId"));
        String source = text(n.get("source"));
        check(source.matches("[A-Za-z0-9_.-]{1,128}"));
        check("membership.activate".equals(text(n.get("handlerId"))));
        UUID eventId = nullableUuid(n.get("eventId")), execution = nullableUuid(n.get("executionId"));
        String origin = text(n.get("origin"));
        check(Set.of("ORIGINAL", "RECOVERY").contains(origin));
        check(!origin.equals("ORIGINAL") || execution == null);
        // 无效恢复头可能没有执行ID；只有有效事件才要求完整恢复关联。
        time(n.get("observedAt"));
        check(text(n.get("errorSummary")).equals(ERRORS.get(text(n.get("errorCode")))));
        check(text(n.get("bodySha256")).matches("[a-f0-9]{64}"));
        JsonNode event = n.get("originalEvent");
        check((eventId == null) == event.isNull());
        if (!event.isNull()) {
            event(event); check(eventId.equals(uuid(event.get("eventId"))));
            check(!origin.equals("RECOVERY") || execution != null);
        }
        JsonNode dl = n.get("deadLetter"); fields(dl, "queue", "reportId", "observationId");
        String queue = text(dl.get("queue")); check(!queue.isEmpty() && queue.length() <= 240);
        check(report.equals(uuid(dl.get("reportId"))) && observation.equals(uuid(dl.get("observationId"))));
        return new Report(report, observation, source, eventId, "membership.activate", execution,
                event, canonical(n), n);
    }
    /** 原文DLX只允许严格白名单OrderPaid，恢复头不授予权限。 */
    public void event(JsonNode n) {
        fields(n, "eventId", "source", "eventType", "schemaVersion", "occurredAt", "payload");
        uuid(n.get("eventId")); version(n.get("schemaVersion")); time(n.get("occurredAt"));
        check("ordivane-demo".equals(text(n.get("source"))) && "OrderPaid".equals(text(n.get("eventType"))));
        JsonNode p = n.get("payload"); fields(p, "orderId", "entitlementType");
        check(text(p.get("orderId")).matches("[A-Za-z0-9_-]{1,100}") && "MEMBERSHIP".equals(text(p.get("entitlementType"))));
    }
    public String canonical(JsonNode n) {
        // 使用既有Jackson原生排序，数组内对象同样排序，不经字符串往返解析改变标量/null。
        return json.writeValueAsString(n);
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String text(JsonNode n) { check(n != null && n.isString()); return n.asString(); }
    public static UUID uuid(JsonNode n) {
        try { String s = text(n); UUID id = UUID.fromString(s); check(id.toString().equals(s)); return id; }
        catch (IllegalArgumentException invalid) { throw IngestionException.invalid(); }
    }
    private UUID nullableUuid(JsonNode n) { return n.isNull() ? null : uuid(n); }
    private void version(JsonNode n) { check(n != null && n.isNumber() && n.decimalValue().compareTo(BigDecimal.ONE) == 0); }
    private void time(JsonNode n) {
        String value=text(n);
        check(value.matches("(?i)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})"));
        try { OffsetDateTime.parse(value); } catch (java.time.DateTimeException invalid) { throw IngestionException.invalid(); }
    }
    public static void fields(JsonNode n, String... names) {
        check(n != null && n.isObject() && n.propertyNames().equals(Set.of(names)));
    }
    public static void check(boolean valid) { if (!valid) { throw IngestionException.invalid(); } }
}
