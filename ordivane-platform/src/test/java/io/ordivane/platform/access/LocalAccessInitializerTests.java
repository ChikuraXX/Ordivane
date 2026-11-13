package io.ordivane.platform.access;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证local显式初始化、重入及冲突时的真实数据库事务回滚。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalAccessInitializerTests {
    private static final String RUN_ID = "BE-020-init-" + UUID.randomUUID();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String SOURCE_CREDENTIAL = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessRepository repository;
    @Autowired private LocalAccessInitializer initializer;
    @Autowired private PasswordEncoder encoder;
    private final Map<AccessRole, UUID> originalIds = new EnumMap<>(AccessRole.class);

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry properties) {
        properties.add("ordivane.access.initialize", () -> "true");
        for (AccessRole role : AccessRole.values()) {
            properties.add("ORDIVANE_ACCESS_" + role.name() + "_USERNAME", () -> username(role));
            properties.add("ORDIVANE_ACCESS_" + role.name() + "_PASSWORD", () -> PASSWORD);
        }
        properties.add("ORDIVANE_ACCESS_SOURCE_ID", () -> RUN_ID);
        properties.add("ORDIVANE_ACCESS_SOURCE_CREDENTIAL", () -> SOURCE_CREDENTIAL);
    }

    @Test
    @Order(1)
    void createsThreeSeparateRolesAndHashedSource() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("ordivane_platform");
        for (AccessRole role : AccessRole.values()) {
            HumanPrincipal user = repository.loadHuman(username(role));
            originalIds.put(role, user.id());
            assertThat(user.role()).isEqualTo(role);
            assertThat(encoder.matches(PASSWORD, user.password())).isTrue();
            assertThat(user.password().equals(PASSWORD)).isFalse();
        }
        assertThat(repository.findSource(SOURCE_CREDENTIAL)).contains(RUN_ID);
        assertThatThrownBy(() -> repository.loadHuman(RUN_ID))
                .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
        System.out.println("BE-020 initializer=PASS; runId=" + RUN_ID + "; roles=3; source separate; stored only hashes");
    }

    @Test
    @Order(2)
    void repeatedInitializationPreservesExistingIds() {
        initializer.run(new DefaultApplicationArguments());
        for (AccessRole role : AccessRole.values()) {
            assertThat(repository.loadHuman(username(role)).id()).isEqualTo(originalIds.get(role));
        }
    }

    @Test
    @Order(3)
    void conflictRollsBackPartialInitializationWithoutOverwritingIdentity() {
        jdbc.update("DELETE FROM access_user WHERE id = ?", originalIds.get(AccessRole.OBSERVER));
        jdbc.update("UPDATE access_user SET role = 'OBSERVER' WHERE id = ?", originalIds.get(AccessRole.OPERATOR));
        try {
            assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("冲突");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM access_user WHERE username = ?", Long.class,
                    username(AccessRole.OBSERVER))).isZero();
            assertThat(repository.loadHuman(username(AccessRole.OPERATOR)).role()).isEqualTo(AccessRole.OBSERVER);
        } finally {
            jdbc.update("UPDATE access_user SET role = 'OPERATOR' WHERE id = ?", originalIds.get(AccessRole.OPERATOR));
            initializer.run(new DefaultApplicationArguments());
        }
    }

    @AfterAll
    void cleanupOwnedInitialization() {
        for (AccessRole role : AccessRole.values()) {
            UUID ownedId = repository.loadHuman(username(role)).id();
            jdbc.update("DELETE FROM access_user WHERE id = ?", ownedId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM access_user WHERE id = ?", Long.class, ownedId)).isZero();
        }
        jdbc.update("DELETE FROM access_source WHERE source_id = ?", RUN_ID);
        assertThat(repository.findSource(SOURCE_CREDENTIAL)).isEmpty();
        System.out.println("BE-020 initializer cleanup=PASS; runId=" + RUN_ID);
    }

    private static String username(AccessRole role) { return RUN_ID + "-" + role.name(); }
}
