package io.ordivane.platform.recovery.execution;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 校验真实HTTP执行响应及错误；使用既有验证器，不称完整OpenAPI认证。 */
class ExecutionContractTests {
    @Test void actualPersistedStatesMatchMachineContract() throws Exception {
        var json=JsonMapper.builder().build();Path root=Path.of("..").toRealPath();
        var contract=json.readTree(Files.readString(root.resolve("contracts/api/executions.openapi.json")));
        var execution=json.createObjectNode();execution.put("$ref","#/components/schemas/Execution");execution.set("components",contract.get("components"));
        var registry=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);var validator=registry.getSchema(execution);
        var error=registry.getSchema(contract.at("/components/schemas/Error"));int count=0,errors=0;Set<String> states=new HashSet<>();
        var samples=json.readTree(Files.readString(root.resolve("ordivane-platform/src/test/resources/execution/actual-contract-samples.json")));
        for(var body:samples.path("executions")) {
            assertThat(validator.validate(body)).isEmpty();count++;states.add(body.path("status").asString());
            var invalid=(tools.jackson.databind.node.ObjectNode)body.deepCopy();invalid.put("status","SUCCEEDED");invalid.put("businessVerified",false);
            assertThat(validator.validate(invalid)).isNotEmpty();
            invalid=(tools.jackson.databind.node.ObjectNode)body.deepCopy();invalid.put("version","1");assertThat(validator.validate(invalid)).isNotEmpty();
        }
        for(var body:samples.path("errors")) { assertThat(error.validate(body)).isEmpty();errors++; }
        assertThat(states).contains("QUEUED","UNKNOWN","BLOCKED","DELIVERED_UNVERIFIED","WAITING","MANUAL_REQUIRED","CANCELLED","SUCCEEDED");
        assertThat(count).isGreaterThan(0);assertThat(errors).isGreaterThan(0);
        // 旧BE-060产物/证据只读；本测试仅消费本任务冻结的真实响应夹具。
    }
}
