package io.ordivane.platform.access;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.ordivane.platform.audit.AuditService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PG与随机HTTP端口；只创建和定向删除BE-020本次runId拥有的行。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.servlet.session.cookie.secure=false", "ordivane.access.initialize=false"})
@Import(AccessBoundaryTests.TestEndpoints.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccessBoundaryTests {
    @LocalServerPort
    private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private TestEndpoints endpoints;
    @Autowired private AuditService audit;
    @Autowired private PlatformTransactionManager transactions;
    private final String runId = "BE-020-" + UUID.randomUUID();
    private final String password = UUID.randomUUID().toString();
    private final String sourceCredential = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    private final Map<AccessRole, UUID> users = new EnumMap<>(AccessRole.class);
    private final List<String> traces = new ArrayList<>();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeAll
    void seedOwnedRows() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("ordivane_platform");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("ordivane_platform");
        System.out.println("BE-020 runId=" + runId + "; randomPort=" + port + "; PostgreSQL="
                + jdbc.queryForObject("SHOW server_version", String.class));
        String hash = encoder.encode(password);
        for (AccessRole role : AccessRole.values()) {
            UUID id = UUID.randomUUID();
            users.put(role, id);
            jdbc.update("INSERT INTO access_user(id, username, password_hash, role) VALUES (?, ?, ?, ?)",
                    id, username(role), hash, role.name());
        }
        jdbc.update("INSERT INTO access_source(source_id, credential_hash) VALUES (?, ?)",
                runId, AccessRepository.hashCredential(sourceCredential));
        endpoints.requesterId = users.get(AccessRole.OPERATOR);
    }

    @AfterAll
    void removeOnlyOwnedRows() {
        for (String trace : traces) { jdbc.update("DELETE FROM audit_record WHERE trace_id = ?", trace); }
        for (UUID id : users.values()) { jdbc.update("DELETE FROM access_user WHERE id = ?", id); }
        jdbc.update("DELETE FROM access_source WHERE source_id = ?", runId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_source WHERE source_id = ?", Long.class, runId)).isZero();
        for (UUID id : users.values()) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM access_user WHERE id = ?", Long.class, id)).isZero();
        }
        System.out.println("BE-020 cleanup=PASS; exact userIds=" + users.values() + "; sourceId=" + runId
                + "; exact traceIds=" + traces);
    }

    @Test
    @Order(1)
    void anonymousIdentityUsesSafeErrorContract() throws Exception {
        var response = send(client(), "GET", "/api/v1/access/me", null, null, null);
        assertThat(response.statusCode()).isEqualTo(401);
        var body = mapper.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.path("message").asString()).isNotBlank();
        assertThat(body.path("traceId").asString()).isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertThat(response.body()).doesNotContain("Exception", "stackTrace", "password");
    }

    @Test
    @Order(2)
    void loginCsrfSessionFixationAndLogoutAreEnforced() throws Exception {
        HttpClient client = client();
        assertThat(send(client, "POST", "/api/v1/access/login", form(AccessRole.OPERATOR), null, null).statusCode()).isEqualTo(403);
        String before = csrf(client);
        String oldSession = cookie(client);
        var login = send(client, "POST", "/api/v1/access/login", form(AccessRole.OPERATOR), before, null);
        assertThat(login.statusCode()).isEqualTo(204);
        assertThat(cookie(client)).isNotEqualTo(oldSession);
        var me = send(client, "GET", "/api/v1/access/me", null, null, null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).path("id").asString()).isEqualTo(users.get(AccessRole.OPERATOR).toString());
        assertThat(me.body()).doesNotContain("password", "credential");
        assertThat(send(client, "POST", "/api/v1/access-test/operate", "", null, null).statusCode()).isEqualTo(403);
        assertThat(send(client, "POST", "/api/v1/access-test/operate", "", "wrong-token", null).statusCode()).isEqualTo(403);
        assertThat(send(client, "POST", "/api/v1/access-test/operate", "", before, null).statusCode()).isEqualTo(403);
        String token = csrf(client);
        assertThat(send(client, "POST", "/api/v1/access-test/operate", "", token, null).statusCode()).isEqualTo(204);
        assertThat(send(client, "POST", "/api/v1/access/logout", "", null, null).statusCode()).isEqualTo(403);
        String session = cookie(client);
        assertThat(send(client, "POST", "/api/v1/access/logout", "", token, null).statusCode()).isEqualTo(204);
        var replay = HttpRequest.newBuilder(uri("/api/v1/access/me")).header("Cookie", session).GET().build();
        var response = client().send(replay, HttpResponse.BodyHandlers.ofString());
        capture(response);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(send(client, "GET", "/api/v1/access/me", null, null, null).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(3)
    void rolesAndOtherApproverAreCheckedOnBackend() throws Exception {
        for (AccessRole role : AccessRole.values()) {
            HttpClient client = login(role);
            String token = csrf(client);
            var operation = send(client, "POST", "/api/v1/access-test/operate", "", token, null);
            assertThat(operation.statusCode()).as("operate role %s", role).isEqualTo(role == AccessRole.OPERATOR ? 204 : 403);
            var approval = send(client, "POST", "/api/v1/access-test/approve", "role=APPROVER", token, null);
            assertThat(approval.statusCode()).as("approve role %s", role).isEqualTo(role == AccessRole.APPROVER ? 204 : 403);
        }
        HttpClient approver = login(AccessRole.APPROVER);
        endpoints.requesterId = users.get(AccessRole.APPROVER);
        try {
            assertThat(send(approver, "POST", "/api/v1/access-test/approve", "", csrf(approver), null).statusCode()).isEqualTo(403);
        } finally { endpoints.requesterId = users.get(AccessRole.OPERATOR); }
    }

    @Test
    @Order(4)
    void sourcesCannotBecomeHumansAndHumansCannotBecomeSources() throws Exception {
        HttpClient source = client();
        String payload = "source=" + runId;
        var accepted = send(source, "POST", "/api/v1/failure-reports", payload, null, sourceCredential);
        assertThat(accepted.statusCode()).isEqualTo(204);
        assertThat(accepted.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(send(source, "POST", "/api/v1/failure-reports", "source=forged", null, sourceCredential).statusCode()).isEqualTo(403);
        assertThat(send(source, "GET", "/api/v1/access/me", null, null, sourceCredential).statusCode()).isEqualTo(401);
        assertThat(send(source, "GET", "/api/v1/access/me", null, null, null).statusCode()).isEqualTo(401);
        assertThat(send(source, "POST", "/api/v1/failure-reports", payload, null, password).statusCode()).isEqualTo(401);
        var human = login(AccessRole.OPERATOR);
        assertThat(send(human, "POST", "/api/v1/failure-reports", payload, csrf(human), null).statusCode()).isEqualTo(401);
        String sourceLogin = "username=" + runId + "&password=" + sourceCredential;
        assertThat(send(source, "POST", "/api/v1/access/login", sourceLogin, csrf(source), null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE access_source SET enabled = false WHERE source_id = ?", runId);
        try {
            assertThat(send(source, "POST", "/api/v1/failure-reports", payload, null, sourceCredential).statusCode()).isEqualTo(401);
        } finally { jdbc.update("UPDATE access_source SET enabled = true WHERE source_id = ?", runId); }
    }

    @Test
    @Order(5)
    void databaseRevocationInvalidatesExistingSession() throws Exception {
        HttpClient client = login(AccessRole.OPERATOR);
        jdbc.update("UPDATE access_user SET role = 'OBSERVER' WHERE id = ?", users.get(AccessRole.OPERATOR));
        try {
            assertThat(send(client, "GET", "/api/v1/access/me", null, null, null).statusCode()).isEqualTo(401);
        } finally { jdbc.update("UPDATE access_user SET role = 'OPERATOR' WHERE id = ?", users.get(AccessRole.OPERATOR)); }
        client = login(AccessRole.OBSERVER);
        jdbc.update("UPDATE access_user SET enabled = false WHERE id = ?", users.get(AccessRole.OBSERVER));
        try {
            assertThat(send(client, "GET", "/api/v1/access/me", null, null, null).statusCode()).isEqualTo(401);
        } finally { jdbc.update("UPDATE access_user SET enabled = true WHERE id = ?", users.get(AccessRole.OBSERVER)); }
    }

    @Test
    @Order(6)
    void auditSurvivesBusinessRollback() {
        String traceId = UUID.randomUUID().toString();
        traces.add(traceId);
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.execute(status -> {
            audit.record(new AuditService.Subject(AuditService.SubjectType.HUMAN, users.get(AccessRole.OPERATOR).toString()),
                    "TEST_OPERATION", "BE-020_TEST", runId, AuditService.Result.FAILURE, traceId);
            throw new IllegalStateException("test-business-rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_record WHERE trace_id = ?", Long.class, traceId)).isEqualTo(1);
    }

    @Test
    @Order(7)
    void invalidLoginAndHttpMethodsDoNotBecomeInternalErrors() throws Exception {
        HttpClient client = client();
        var longPassword = send(client, "POST", "/api/v1/access/login",
                "username=" + username(AccessRole.OPERATOR) + "&password=" + "x".repeat(100), csrf(client), null);
        assertThat(longPassword.statusCode()).isEqualTo(401);
        client = login(AccessRole.OPERATOR);
        var wrongMethod = send(client, "POST", "/api/v1/access/me", "", csrf(client), null);
        assertThat(wrongMethod.statusCode()).isEqualTo(405);
        assertThat(mapper.readTree(wrongMethod.body()).path("code").asString()).isEqualTo("INVALID_REQUEST");
        var spoof = HttpRequest.newBuilder(uri("/api/v1/access/me")).header("X-Trace-Id", "client-supplied-value").GET().build();
        var response = client.send(spoof, HttpResponse.BodyHandlers.ofString());
        capture(response);
        assertThat(response.headers().firstValue("X-Trace-Id").orElseThrow()).isNotEqualTo("client-supplied-value");
    }

    @Test
    @Order(8)
    void errorsAndAuditAreCorrelatedStructuredAndSecretFree() throws Exception {
        HttpClient client = login(AccessRole.OPERATOR);
        var failure = send(client, "GET", "/api/v1/access-test/failure", null, null, null);
        assertThat(failure.statusCode()).isEqualTo(500);
        var body = mapper.readTree(failure.body());
        assertThat(body.path("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.path("traceId").asString()).isNotBlank();
        assertThat(failure.body()).doesNotContain("sentinel-internal-detail", "Exception", "stack");
        List<Map<String, Object>> records = new ArrayList<>();
        for (String trace : traces) { records.addAll(jdbc.queryForList("SELECT * FROM audit_record WHERE trace_id = ?", trace)); }
        assertThat(records).isNotEmpty();
        assertThat(records).extracting(row -> row.get("subject_type")).contains("HUMAN", "SOURCE", "ANONYMOUS");
        assertThat(records).extracting(row -> row.get("result")).contains("SUCCESS", "DENIED");
        assertThat(records).extracting(row -> row.get("action")).contains("LOGIN", "LOGOUT", "AUTHORIZE", "SOURCE_AUTHENTICATE");
        for (var row : records) {
            for (String field : List.of("subject_type", "subject_id", "action", "object_type", "object_id", "result", "trace_id")) {
                assertThat(row.get(field).toString()).as(field).isNotBlank();
            }
        }
        String serialized = records.toString();
        assertThat(serialized.contains(password)).isFalse();
        assertThat(serialized.contains(sourceCredential)).isFalse();
        assertThat(serialized.contains(AccessRepository.hashCredential(sourceCredential))).isFalse();
        assertThat(serialized).doesNotContain("Authorization", "Bearer", "password_hash", "sentinel-internal-detail");
        System.out.println("BE-020 audit=PASS; ownedAuditCount=" + records.size() + "; required fields and credential exclusion verified");
    }

    private String username(AccessRole role) { return runId + "-" + role.name(); }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private String form(AccessRole role) { return "username=" + username(role) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8); }

    private HttpClient login(AccessRole role) throws Exception {
        HttpClient client = client();
        assertThat(send(client, "POST", "/api/v1/access/login", form(role), csrf(client), null).statusCode()).isEqualTo(204);
        return client;
    }

    private String cookie(HttpClient client) {
        var manager = (CookieManager) client.cookieHandler().orElseThrow();
        return manager.getCookieStore().getCookies().stream().filter(value -> value.getName().equals("JSESSIONID"))
                .map(value -> value.getName() + "=" + value.getValue()).findFirst().orElseThrow();
    }

    private String csrf(HttpClient client) throws Exception {
        var response = send(client, "GET", "/api/v1/access/csrf", null, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode token = mapper.readTree(response.body());
        assertThat(token.path("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
        return token.path("token").asString();
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String form,
                                       String csrf, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15));
        if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        if (method.equals("GET")) { request.GET(); }
        else { request.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)); }
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        capture(response);
        return response;
    }

    private void capture(HttpResponse<String> response) {
        response.headers().firstValue("X-Trace-Id").ifPresent(traces::add);
    }

    /** 测试上下文专用的受保护业务边界，生产应用不包含这些路由。 */
    @RestController
    static class TestEndpoints {
        private final AccessPolicy policy;
        private UUID requesterId;

        TestEndpoints(AccessPolicy policy) { this.policy = policy; }

        @PostMapping("/api/v1/access-test/operate")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void operate() { policy.requireOperator(); }

        @PostMapping("/api/v1/access-test/approve")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void approve() { policy.requireOtherApprover(requesterId); }

        @PostMapping("/api/v1/failure-reports")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void report(@RequestParam String source) { policy.requireSource(source); }

        @GetMapping("/api/v1/access-test/failure")
        void failure() { throw new IllegalStateException("sentinel-internal-detail"); }
    }
}
