package io.ordivane.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 平台独立启动入口；业务能力由后续模块任务接入。 */
@SpringBootApplication
public class PlatformApplication {
    /** 使用显式进程环境启动平台应用。 */
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
