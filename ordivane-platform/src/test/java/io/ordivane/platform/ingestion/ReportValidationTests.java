package io.ordivane.platform.ingestion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 冻结样例及严格信任边界回归；不需要外部服务。 */
class ReportValidationTests {
    @Test
    void canonicalSortsObjectsInsideArraysWhilePreservingNullAndScalarTypes() {
        var decoder=new ReportDecoder();
        var node=decoder.parse("{\"z\":null,\"a\":[{\"z\":true,\"a\":\"1\"}],\"n\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(decoder.canonical(node)).isEqualTo("{\"a\":[{\"a\":\"1\",\"z\":true}],\"n\":1,\"z\":null}");
    }
    @Test
    void acceptsFrozenExampleButRejectsCoercionAndUnknownFields() throws Exception {
        String body = Files.readString(Path.of("../contracts/events/failure-report-v1.example.json"));
        var decoder = new ReportDecoder();
        assertThat(decoder.report(body.getBytes(StandardCharsets.UTF_8)).reportId()).isNotNull();
        for (String invalid : new String[] {
                body.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""),
                body.replaceFirst("\\{", "{\"unknown\":true,"),
                body.replace("\"schemaVersion\": 1", "\"schemaVersion\": null") }) {
            assertThatThrownBy(() -> decoder.report(invalid.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IngestionException.class);
        }
        assertThatThrownBy(() -> decoder.report(new byte[32769])).isInstanceOf(IngestionException.class);
        assertThatThrownBy(() -> decoder.report(new byte[] {(byte)0xc3, 0x28})).isInstanceOf(IngestionException.class);
    }
    @Test
    void rejectsMissingNullDuplicateTrailingRelationshipsAndNonExactVersions() throws Exception {
        String body=Files.readString(Path.of("../contracts/events/failure-report-v1.example.json"));
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var decoder=new ReportDecoder();
        for(String field: new String[]{"reportId","observationId","source","handlerId","observedAt","errorCode","errorSummary","bodySha256","deadLetter"}) {
            var missing=(tools.jackson.databind.node.ObjectNode)json.readTree(body);missing.remove(field);
            assertThatThrownBy(()->decoder.report(json.writeValueAsBytes(missing))).isInstanceOf(IngestionException.class);
            var nulled=(tools.jackson.databind.node.ObjectNode)json.readTree(body);nulled.putNull(field);
            assertThatThrownBy(()->decoder.report(json.writeValueAsBytes(nulled))).isInstanceOf(IngestionException.class);
        }
        for(String invalid:new String[]{body+"{}",body.replaceFirst("\\{","{\"schemaVersion\":1,"),
                body.replace("2026-10-18T04:00:00Z","2026-10-18"),
                body.replace("\"eventId\": null","\"eventId\": \"00000000-0000-4000-8000-000000000001\""),
                body.replace("\"origin\": \"ORIGINAL\"","\"origin\": \"OTHER\""),
                body.replace("会员处理未完成","unknown"),
                body.replace("\"schemaVersion\": 1","\"schemaVersion\": 1.00000000000000000000001"),
                body.replace("\"schemaVersion\": 1","\"schemaVersion\": true")}) {
            // 样例不是PROCESSING_FAILED，不添加无变化的输入。
            if(!invalid.equals(body)) { assertThatThrownBy(()->decoder.report(invalid.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IngestionException.class); }
        }
        for(String valid:new String[]{"1","1.0","1e0"}) {
            assertThat(decoder.report(body.replace("\"schemaVersion\": 1","\"schemaVersion\": "+valid).getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
    }
}
