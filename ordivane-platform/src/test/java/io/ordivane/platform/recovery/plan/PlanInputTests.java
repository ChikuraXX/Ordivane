package io.ordivane.platform.recovery.plan;

import io.ordivane.platform.ingestion.ReportDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 严格边界回归，避免标量转换和隐式原事件编辑。 */
class PlanInputTests {
    private final ReportDecoder decoder=new ReportDecoder();
    @Test void rejectsCoercionAndUnknownFields() {
        for(String value:new String[]{"\"1\"","null","true","1.0000000000000000001","0","2147483648"})
            assertThatThrownBy(()->PlanInput.positive(decoder.parse(value.getBytes(StandardCharsets.UTF_8)),Integer.MAX_VALUE)).isInstanceOf(RuntimeException.class);
        assertThat(PlanInput.positive(decoder.parse("1e0".getBytes(StandardCharsets.UTF_8)),10)).isEqualTo(1);
    }
    @Test void fourActionsHaveStrictParameters() {
        String base="{\"action\":\"%s\",\"parameters\":%s,\"evidenceIds\":[\"00000000-0000-0000-0000-000000000001\"],\"previous\":null}";
        for(String action:PlanInput.ACTIONS) {
            String parameters=action.equals("WAIT_AND_RECHECK")?"{\"delaySeconds\":5}":"{}";
            PlanInput.create(decoder.parse(base.formatted(action,parameters).getBytes(StandardCharsets.UTF_8)));
            assertThatThrownBy(()->PlanInput.create(decoder.parse(base.formatted(action,"{\"originalEvent\":{}}").getBytes(StandardCharsets.UTF_8)))).isInstanceOf(RuntimeException.class);
        }
    }
}
