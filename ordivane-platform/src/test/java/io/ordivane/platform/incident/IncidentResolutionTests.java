package io.ordivane.platform.incident;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 校验真实OPEN/RESOLVED投影与解决依据的契约兼容性；并发事务由真实隔离矩阵验证。 */
class IncidentResolutionTests {
    @Test void actualIncidentStatesAndResolutionReferencesMatchContract() throws Exception {
        var json=JsonMapper.builder().build();Path root=Path.of("..").toRealPath();
        var contract=json.readTree(Files.readString(root.resolve("contracts/api/incidents.openapi.json")));
        var schema=json.createObjectNode();schema.put("$ref","#/components/schemas/IncidentDetail");schema.set("components",contract.path("components"));
        var validator=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema);
        var samples=json.readTree(Files.readString(Path.of("src/test/resources/execution/actual-contract-samples.json")));
        boolean open=false,resolved=false;
        for(var incident:samples.path("incidents")) {
            assertThat(validator.validate(incident)).isEmpty();
            if("RESOLVED".equals(incident.path("status").asString())) {
                resolved=true;assertThat(incident.path("resolved_at").isString()).isTrue();
                assertThat(incident.path("resolved_execution_id").isString()).isTrue();assertThat(incident.path("resolution_observation_id").isString()).isTrue();
            } else { open=true;assertThat(incident.path("resolved_at").isNull()).isTrue(); }
        }
        assertThat(open).isTrue();assertThat(resolved).isTrue();
    }
}
