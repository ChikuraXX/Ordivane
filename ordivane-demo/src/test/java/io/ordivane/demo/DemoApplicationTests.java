package io.ordivane.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

/** 不使用替代数据库伪装真实启动；集成健康检查由本地验收脚本负责。 */
class DemoApplicationTests {
    @Test
    void declaresApplicationEntryPoint() {
        assertThat(DemoApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
    }
}

