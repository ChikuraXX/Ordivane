package io.ordivane.demo.simulation;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import io.ordivane.demo.DemoApplication;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import static org.junit.jupiter.api.Assertions.*;

/** 真实本机依赖与随机HTTP入口；每轮独占测试拓扑，不消费开发队列。 */
class FaultIntegrationTests {
    @Test
    void localFaultCanBeEnabledAndReset() throws Exception {
        String prefix = "ordivane.test.be011." + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        SpringApplication app = new SpringApplication(DemoApplication.class);
        ConfigurableApplicationContext context = app.run("--server.port=0", "--spring.profiles.active=local",
                "--demo.simulation.enabled=true", "--demo.messaging.prefix=" + prefix,
                "--demo.outbox.enabled=false", "--ORDIVANE_DEMO_API_TOKEN=" + token);
        try {
            String base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            String path = "/api/v1/simulations/faults/" + UUID.randomUUID();
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"scenario\":\"DOWNSTREAM_UNAVAILABLE\"}")).build();
            assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            context.getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class).stop();
            var admin = new RabbitAdmin(context.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class));
            for (String suffix : List.of(".membership.original", ".membership.recovery", ".membership.failed")) { admin.deleteQueue(prefix + suffix); }
            for (String suffix : List.of(".events", ".recovery", ".dead")) { admin.deleteExchange(prefix + suffix); }
            context.close();
        }
    }
}
