package io.ordivane.platform.recovery.plan;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 用项目既有2020-12验证器核对真实HTTP输出，非完整OpenAPI规范认证。 */
class ContractResponseTests {
    @Test void actualResponsesMatchFrozenMachineContract() throws Exception {
        var json=JsonMapper.builder().build();Path root=Path.of("..").toRealPath();
        var contract=json.readTree(Files.readString(root.resolve("contracts/api/recovery-plans.openapi.json")));
        int count=0;
        try(var paths=Files.list(root.resolve("workflow/backend/tasks/BE-050/evidence"))) {
            for(Path directory:paths.filter(p->p.getFileName().toString().startsWith("run-")).toList()) {
                var run=json.readTree(Files.readString(directory.resolve("results.json")));
                if(!run.path("result").asString().equals("PASS")) continue;
                for(var check:run.path("checks")) {
                    String name=check.path("name").asString();int status=check.path("actual").asInt();
                    if((status!=200 && status!=201) || !name.contains("recovery-plans") || !check.path("body").isObject()) continue;
                    String kind=name.endsWith("/prechecks")?"Precheck":"Plan";
                    var schema=json.createObjectNode();schema.put("$ref","#/components/schemas/"+kind);schema.set("components",contract.get("components"));
                    var validator=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema);
                    var errors=validator.validate(check.get("body"));
                    assertThat(errors).as(name).isEmpty();count++;
                    // 证明本地引用实际生效：真实结果不能变成执行授权，不能把版本变成字符串。
                    var invalid=(tools.jackson.databind.node.ObjectNode)check.get("body").deepCopy();
                    invalid.put("executionAuthorized",true);assertThat(validator.validate(invalid)).isNotEmpty();
                    invalid=(tools.jackson.databind.node.ObjectNode)check.get("body").deepCopy();
                    invalid.put("version","1");assertThat(validator.validate(invalid)).isNotEmpty();
                }
            }
        }
        assertThat(count).isGreaterThan(40);
        Files.writeString(root.resolve("workflow/backend/tasks/BE-050/evidence/contract-response-validation.json"),json.writeValueAsString(java.util.Map.of("result","PASS","responseSamples",count,"validator","networknt 3.0.8 / JSON Schema 2020-12","fullOpenApiCertification","NOT_RUN")));
    }
}
