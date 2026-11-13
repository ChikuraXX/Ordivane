package io.ordivane.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 演示独立启动入口；业务能力由后续模块任务接入。 */
@SpringBootApplication
public class DemoApplication {
    /** 使用显式进程环境启动演示应用。 */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

