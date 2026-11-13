package io.ordivane.platform.recovery.plan;

import io.ordivane.platform.PlatformApplication;
import io.ordivane.platform.access.AccessRepository;
import io.ordivane.platform.ingestion.IngestionException;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 由真实HTTP探针传入自有schema和计划；直接调用后继内部入口，不向生产增加测试API。 */
class ExecutionRecheckTests {
    @Test void exactApprovedVersionAndCurrentConfiguration() throws Exception {
        String input=System.getenv("BE050_RECHECK_INPUT");assertThat(input).isNotBlank();
        var json=JsonMapper.builder().build();var plan=json.readTree(Files.readString(Path.of(input)));
        try(var app=new SpringApplicationBuilder(PlatformApplication.class).run("--server.port=0","--spring.profiles.active=local",
                "--ordivane.access.initialize=false","--ordivane.ingestion.enabled=false","--logging.level.root=WARN")) {
            var user=app.getBean(AccessRepository.class).loadHuman(System.getenv("ORDIVANE_ACCESS_OPERATOR_USERNAME"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities()));
            var service=app.getBean(RecoveryPlanService.class);UUID id=UUID.fromString(plan.get("id").asString());
            int version=plan.get("version").intValue();String digest=plan.get("digest").asString();
            if("changed".equals(System.getenv("BE050_RECHECK_MODE"))) {
                assertThatThrownBy(()->service.recheckBeforeExecution(id,version,digest)).isInstanceOf(IngestionException.class)
                        .satisfies(e->assertThat(((IngestionException)e).status()).isEqualTo(409));
            } else {
                var result=service.recheckBeforeExecution(id,version,digest);
                assertThat(result.get("passed").asBoolean()).isTrue();assertThat(result.get("executionAuthorized").asBoolean()).isFalse();
                assertThat(result.get("deferredChecks").toString()).contains("CONFLICTING_EXECUTION","ATOMIC_EXECUTION_ELIGIBILITY");
                assertThatThrownBy(()->service.recheckBeforeExecution(id,version+1,digest)).isInstanceOf(IngestionException.class);
                assertThatThrownBy(()->service.recheckBeforeExecution(id,version,"0".repeat(64))).isInstanceOf(IngestionException.class);
                SecurityContextHolder.clearContext();assertThatThrownBy(()->service.recheckBeforeExecution(id,version,digest)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            }
        } finally { SecurityContextHolder.clearContext(); }
    }
}
