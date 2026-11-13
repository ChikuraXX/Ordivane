package io.ordivane.platform.recovery.execution;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 传输映射只由本机受信启动参数决定，不能绕过生产固定目标。 */
class ExecutionTransportTests {
    @Test void fixedProductionAndExplicitLocalMapping() {
        assertThat(ExecutionTransport.resolve(false,"",false).exchange()).isEqualTo("ordivane.demo.recovery");
        var mapped=ExecutionTransport.resolve(true,"ordivane.test.be060.abc123",true);
        assertThat(mapped.exchange()).isEqualTo("ordivane.test.be060.abc123.recovery");
        assertThat(mapped.routingKey()).isEqualTo("membership.activate");
        assertThat(mapped.fingerprint()).isNotEqualTo(ExecutionTransport.resolve(true,"ordivane.test.be060.def456",true).fingerprint());
    }
    @Test void rejectsNonLocalImplicitAndUnsafeOverrides() {
        assertThatThrownBy(()->ExecutionTransport.resolve(true,"ordivane.test.be060.abc123",false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExecutionTransport.resolve(false,"ordivane.test.be060.abc123",true)).isInstanceOf(IllegalArgumentException.class);
        for(String prefix:new String[]{"","ordivane.demo","ordivane.test.fe050.abc","ordivane.test.be060.a/evil","ordivane.test.be060.a.b"})
            assertThatThrownBy(()->ExecutionTransport.resolve(true,prefix,true)).isInstanceOf(IllegalArgumentException.class);
    }
}
