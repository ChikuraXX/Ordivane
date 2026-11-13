package io.ordivane.platform.configuration;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 配置边界回归：真实样例副本，不调用业务队列或数据库。 */
class ConfigurationLoaderTests {
    @TempDir Path root;
    private final Map<String,String> env = new HashMap<>(Map.of(
            "ORDIVANE_RABBITMQ_URI", "amqp://user:secret-one@localhost:5672/",
            "ORDIVANE_DEMO_API_TOKEN", "secret-token-one"));

    @BeforeEach void fixture() throws Exception {
        Path project = Path.of("..").toAbsolutePath().normalize();
        for (String directory : List.of("schemas", "scenarios", "prompts", "runbooks")) {
            Files.createDirectories(root.resolve(directory));
            try (var files = Files.list(project.resolve(directory))) {
                for (Path file : files.toList()) Files.writeString(root.resolve(directory).resolve(file.getFileName()),Files.readString(file).replace("\r\n","\n"));
            }
        }
        Files.writeString(root.resolve("ordivane.yml"),Files.readString(project.resolve("ordivane.yml")).replace("\r\n","\n"));
    }

    private ConfigurationLoader.Loaded load() { return new ConfigurationLoader().load(root, env); }
    private void replace(String file, String from, String to) throws Exception {
        Path path = root.resolve(file); Files.writeString(path, Files.readString(path).replace(from, to));
    }
    private void rejects(String field) {
        assertThatThrownBy(this::load).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(field).hasMessageNotContaining("secret-one").hasMessageNotContaining("secret-token-one");
    }

    @Test void disabledModelAndSecretRotationProduceSameNonSecretVersion() {
        var first = load();
        assertThat(first.snapshot()).doesNotContain("secret-one", "secret-token-one", "ORDIVANE_MODEL");
        env.put("ORDIVANE_RABBITMQ_URI", "amqp://other:secret-two@localhost:5672/");
        env.put("ORDIVANE_DEMO_API_TOKEN", "secret-token-two");
        assertThat(load().digest()).isEqualTo(first.digest());
        assertThat(first.capabilities()).allMatch(c -> !c.executable());
    }
    @Test void nonSecretConnectionAndContentChangeVersion() throws Exception {
        String first = load().digest();
        env.put("ORDIVANE_RABBITMQ_URI", "amqp://user:secret-one@127.0.0.1:5672/");
        assertThat(load().digest()).isNotEqualTo(first);
        String next = load().digest();
        Files.writeString(root.resolve("prompts/membership-diagnosis.md"), "\n附加事实\n", StandardOpenOption.APPEND);
        assertThat(load().digest()).isNotEqualTo(next);
        next = load().digest();
        Files.writeString(root.resolve("runbooks/membership-activation.md"), "\n附加手册\n", StandardOpenOption.APPEND);
        assertThat(load().digest()).isNotEqualTo(next);
    }
    @Test void missingConnectionSecretLocatesFieldWithoutValue() { env.remove("ORDIVANE_DEMO_API_TOKEN"); rejects("/connections/membership/api_token"); }
    @Test void unknownFieldsAndTypesAreRejected() throws Exception {
        replace("ordivane.yml", "enabled: false", "enabled: 'false'"); rejects("/model/enabled");
        replace("ordivane.yml", "enabled: 'false'", "enabled: false\n  unexpected: secret-one"); rejects("/model");
    }
    @Test void schemaNumbersMustNotRoundFractionalVersionsIntoIntegers() throws Exception {
        replace("ordivane.yml","schema_version: 1","schema_version: 1.000000000000000000000001");
        rejects("/schema_version");
    }
    @Test void duplicateKeysTagsAliasesAndMultipleDocumentsAreRejected() throws Exception {
        String original = Files.readString(root.resolve("ordivane.yml"));
        for (String bad : List.of("schema_version: 1\nschema_version: 1", "x: !!java/object secret-one", "x: &a [1]\ny: *a", original + "\n---\nx: 1")) {
            Files.writeString(root.resolve("ordivane.yml"), bad); rejects("ordivane.yml");
        }
    }
    @Test void boundedYamlAndTextInputs() throws Exception {
        Files.writeString(root.resolve("ordivane.yml"), "x: " + "[".repeat(40) + "0" + "]".repeat(40)); rejects("ordivane.yml");
        Files.writeString(root.resolve("ordivane.yml"), "x".repeat(262145)); rejects("ordivane.yml");
    }
    @Test void dangerousTagAndDuplicateKeyLocateLineWithoutEchoingInput() throws Exception {
        Files.writeString(root.resolve("ordivane.yml"),"schema_version: 1\nschema_version: 1\n"); rejects("line:2");
        Files.writeString(root.resolve("ordivane.yml"),"model:\n  api_key: !danger secret-one\n"); rejects("line:2");
    }
    @Test void traversalAbsoluteAndEscapingLinkAreRejected() throws Exception {
        for (String path : List.of("../scenarios", "C:/Windows", "/tmp", "scenarios/../../outside")) {
            replace("ordivane.yml", "scenario_directory: scenarios", "scenario_directory: " + path); rejects("/scenario_directory");
            replace("ordivane.yml", "scenario_directory: " + path, "scenario_directory: scenarios");
        }
    }
    @Test void allActionsRequireOtherPersonApprovalAndReferencesMustExist() throws Exception {
        replace("scenarios/membership-activation.yml", "forbid_self_approval: true", "forbid_self_approval: false"); rejects("forbid_self_approval");
        replace("scenarios/membership-activation.yml", "forbid_self_approval: false", "forbid_self_approval: true");
        replace("scenarios/membership-activation.yml", "- no-conflicting-execution", "- arbitrary-script"); rejects("replay_prechecks");
    }
    @Test void invalidTimeoutAndC01MappingsAreRejected() throws Exception {
        replace("scenarios/membership-activation.yml", "interval_seconds: 5", "interval_seconds: 61"); rejects("interval_seconds");
        replace("scenarios/membership-activation.yml", "interval_seconds: 61", "interval_seconds: 5");
        replace("ordivane.yml", "exchange: ordivane.demo.recovery", "exchange: ordivane.recovery"); rejects("exchange");
    }
    @Test void twoSyntheticScenariosMatchExactlyAndAmbiguityIsRejected() throws Exception {
        Path first = root.resolve("scenarios/membership-activation.yml");
        Files.delete(first);
        for(String name:List.of("first.yml","second.yml")) Files.copy(Path.of("src/test/resources/configuration/scenarios/"+name),root.resolve("scenarios/"+name));
        var loaded = load();
        assertThat(loaded.match("membership-demo", "SyntheticPaidA", 1, "membership.activate")).contains("synthetic-first");
        assertThat(loaded.match("membership-demo", "SyntheticPaidB", 1, "membership.activate")).contains("synthetic-second");
        assertThat(loaded.match("ordivane-demo", "SyntheticPaidA", 1, "membership.activate")).isEmpty();
        replace("scenarios/second.yml","SyntheticPaidB","SyntheticPaidA"); rejects("/scenario/match");
    }

    @Test void disabledModelCanOmitAllModelConnectionFields() throws Exception {
        String before=load().digest();
        for(String line:List.of("  base_url: ${ORDIVANE_MODEL_BASE_URL}\n", "  model_name: ${ORDIVANE_MODEL_NAME}\n", "  api_key: ${ORDIVANE_MODEL_API_KEY}\n")) replace("ordivane.yml",line,"");
        assertThat(load().digest()).isEqualTo(before);
        replace("ordivane.yml","enabled: false","enabled: true"); rejects("/model");
    }
    @Test void forbiddenExpressionsAndUnsafeNetworkAddressesLocateField() throws Exception {
        replace("ordivane.yml","name: Ordivane","name: ${ORDIVANE_DEMO_API_TOKEN}"); rejects("/project/name");
        replace("ordivane.yml","name: ${ORDIVANE_DEMO_API_TOKEN}","name: Ordivane");
        for(String value:List.of("http://example.org","https://secret-one@example.org","file:///etc/passwd","https://example.org?token=secret-one")) {
            replace("ordivane.yml","base_url: http://localhost:8081","base_url: "+value); rejects("/connections/membership/base_url");
            replace("ordivane.yml","base_url: "+value,"base_url: http://localhost:8081");
        }
        replace("scenarios/membership-activation.yml","prompts/membership-diagnosis.md","${ORDIVANE_DEMO_API_TOKEN}"); rejects("/scenario/diagnosis/prompt");
    }
    @Test void duplicateIdsUnregisteredCapabilitiesAndMissingApprovalsAreRejected() throws Exception {
        Path scenario=root.resolve("scenarios/membership-activation.yml");
        Files.copy(scenario,root.resolve("scenarios/duplicate.yml")); rejects("/scenario/id"); Files.delete(root.resolve("scenarios/duplicate.yml"));
        replace("ordivane.yml","- event-details","- arbitrary-tool"); rejects("/registry/tools");
        replace("ordivane.yml","- arbitrary-tool","- event-details");
        replace("scenarios/membership-activation.yml","        - VERIFY_ONLY\n",""); rejects("/scenario/recovery/approval/required_for");
    }
    @Test void modelBudgetsAndTrustedReportSourceAreValidated() throws Exception {
        replace("ordivane.yml","enabled: false","enabled: true"); rejects("/model/base_url");
        env.put("ORDIVANE_MODEL_BASE_URL","https://model.example.org/v1"); env.put("ORDIVANE_MODEL_NAME","test-model"); env.put("ORDIVANE_MODEL_API_KEY","secret-model");
        assertThat(load().snapshot()).doesNotContain("secret-model");
        replace("scenarios/membership-activation.yml","timeout_seconds: 60","timeout_seconds: 10"); rejects("/scenario/diagnosis/timeout_seconds");
        replace("scenarios/membership-activation.yml","timeout_seconds: 10","timeout_seconds: 60");
        replace("scenarios/membership-activation.yml","source: membership-demo","source: ordivane-demo"); rejects("/scenario/match/source");
    }
    @Test void whitespaceDoesNotChangeVersionAndPublishedCollectionsAreImmutable() throws Exception {
        var first=load(); Files.writeString(root.resolve("ordivane.yml"),"\n# 无业务变化\n",StandardOpenOption.APPEND);
        assertThat(load().digest()).isEqualTo(first.digest());
        assertThatThrownBy(()->first.capabilities().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->first.matches().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void credentialFilesCannotBeImportedAsPromptOrRunbookContent() throws Exception {
        Files.writeString(root.resolve(".env"),"ORDIVANE_DEMO_API_TOKEN=secret-token-one\n");
        replace("scenarios/membership-activation.yml","prompts/membership-diagnosis.md",".env");
        rejects("/scenario/diagnosis/prompt");
    }
    @Test void nonSecretFieldCannotImportAnotherCapabilitiesCredential() throws Exception {
        replace("ordivane.yml","enabled: false","enabled: true");
        env.put("ORDIVANE_MODEL_BASE_URL","https://model.example.org"); env.put("ORDIVANE_MODEL_API_KEY","secret-model");
        env.put("ORDIVANE_PLATFORM_DB_PASSWORD","secret-database");
        replace("ordivane.yml","${ORDIVANE_MODEL_NAME}","${ORDIVANE_PLATFORM_DB_PASSWORD}");
        rejects("/model/model_name");
    }
    @Test void windowsJunctionCannotEscapeWorkRoot() throws Exception {
        Path outside=Files.createTempDirectory("be040-outside-"); Path link=root.resolve("escape");
        try {
            if(System.getProperty("os.name").startsWith("Windows")) {
                Process process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
                assertThat(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
            } else Files.createSymbolicLink(link,outside);
            replace("ordivane.yml","scenario_directory: scenarios","scenario_directory: escape"); rejects("/scenario_directory");
        } finally { Files.deleteIfExists(link); Files.deleteIfExists(outside); }
    }
}
