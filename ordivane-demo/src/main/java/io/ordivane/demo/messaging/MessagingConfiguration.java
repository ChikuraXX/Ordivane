package io.ordivane.demo.messaging;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 演示专用持久化拓扑，测试通过前缀隔离；不使用其他项目队列。 */
@Configuration
@EnableScheduling
public class MessagingConfiguration {
    /** 原始、固定会员恢复目标和死信拓扑，不自动删除持久消息。 */
    @Bean
    public Declarables membershipTopology(@Value("${demo.messaging.prefix:ordivane.demo}") String prefix) {
        DirectExchange exchange = new DirectExchange(prefix + ".events", true, false);
        DirectExchange recovery = new DirectExchange(prefix + ".recovery", true, false);
        DirectExchange dead = new DirectExchange(prefix + ".dead", true, false);
        Queue original = QueueBuilder.durable(prefix + ".membership.original")
                .deadLetterExchange(dead.getName()).deadLetterRoutingKey("membership.failed").build();
        Queue replay = QueueBuilder.durable(prefix + ".membership.recovery")
                .deadLetterExchange(dead.getName()).deadLetterRoutingKey("membership.failed").build();
        Queue failed = QueueBuilder.durable(prefix + ".membership.failed").build();
        return new Declarables(exchange, recovery, dead, original, replay, failed,
                BindingBuilder.bind(original).to(exchange).with("order.paid.v1"),
                BindingBuilder.bind(replay).to(recovery).with("membership.activate"),
                BindingBuilder.bind(failed).to(dead).with("membership.failed"));
    }
}
