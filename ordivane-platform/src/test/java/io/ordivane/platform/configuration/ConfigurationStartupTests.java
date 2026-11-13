package io.ordivane.platform.configuration;

import io.ordivane.platform.PlatformApplication;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 真实平台PG独占schema、随机HTTP端口；关闭入站消费，不连接演示库或操作MQ。 */
class ConfigurationStartupTests {
    private final JsonMapper json=JsonMapper.builder().build();

    @Test void isolatedMigrationStartupReadOnlyAndImmutableHistory() throws Exception {
        String run=UUID.randomUUID().toString().replace("-", "").substring(0,12);
        String schema="be040_"+run;
        Path project=Path.of("..").toRealPath();
        Path root=project.resolve("work/be040/"+run);
        Path evidence=project.resolve("workflow/backend/tasks/BE-040/evidence");
        Map<String,Object> result=new LinkedHashMap<>(); result.put("runId",run); result.put("schema",schema); result.put("root",root.toString()); result.put("pid",ProcessHandle.current().pid());
        Files.writeString(evidence.resolve("runtime-"+run+"-resources.json"),json.writeValueAsString(result));
        String base="jdbc:postgresql://"+System.getenv().getOrDefault("ORDIVANE_PG_HOST","127.0.0.1")+":"+System.getenv().getOrDefault("ORDIVANE_PG_PORT","5432")+"/ordivane_platform";
        String password=System.getenv("ORDIVANE_PLATFORM_DB_PASSWORD");
        var adminData=new DriverManagerDataSource(base,"ordivane_platform",password);
        JdbcTemplate admin=new JdbcTemplate(adminData);
        assertThat(admin.queryForObject("SELECT current_database()",String.class)).isEqualTo("ordivane_platform");
        assertThat(admin.queryForObject("SELECT current_user",String.class)).isEqualTo("ordivane_platform");
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?",Integer.class,schema)).isZero();
        admin.execute("CREATE SCHEMA "+schema+" AUTHORIZATION ordivane_platform");
        String url=base+"?currentSchema="+schema;
        var isolatedData=new DriverManagerDataSource(url,"ordivane_platform",password);
        JdbcTemplate isolated=new JdbcTemplate(isolatedData);
        boolean completed=false;
        try {
            String current=isolated.queryForObject("SELECT current_schema()",String.class);
            String search=isolated.queryForObject("SHOW search_path",String.class);
            assertThat(current).isEqualTo(schema); assertThat(search).isEqualTo(schema);
            result.put("beforeMigration",Map.of("database","ordivane_platform","currentSchema",current,"searchPath",search));
            Files.writeString(evidence.resolve("runtime-"+run+"-resources.json"),json.writeValueAsString(result));
            Files.createDirectories(root);
            for(String directory:List.of("schemas","scenarios","prompts","runbooks")) {
                Files.createDirectory(root.resolve(directory));
                try(var paths=Files.list(project.resolve(directory))) { for(Path path:paths.toList()) Files.copy(path,root.resolve(directory).resolve(path.getFileName())); }
            }
            Files.copy(project.resolve("ordivane.yml"),root.resolve("ordivane.yml"));
            for(String name:List.of("ORDIVANE_MODEL_API_KEY","ORDIVANE_MODEL_BASE_URL","ORDIVANE_MODEL_NAME")) assertThat(System.getenv(name)).isNull();
            String observer="be040."+run+".observer", secret=UUID.randomUUID().toString();
            String digest;
            try(ConfigurableApplicationContext app=start(root,url,schema,observer,secret,true)) {
                int port=((WebServerApplicationContext)app).getWebServer().getPort(); result.put("port",port);
                ConfigurationLoader.Loaded loaded=app.getBean(ConfigurationLoader.Loaded.class); digest=loaded.digest();
                assertThat(loaded.snapshot()).doesNotContain(System.getenv("ORDIVANE_DEMO_API_TOKEN"));
                assertThat(isolated.queryForObject("SELECT count(*) FROM configuration_version",Integer.class)).isEqualTo(1);
                var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
                URI host=URI.create("http://127.0.0.1:"+port);
                assertThat(client.send(HttpRequest.newBuilder(host.resolve("/actuator/health")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                var csrf=json.readTree(client.send(HttpRequest.newBuilder(host.resolve("/api/v1/access/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
                String body="username="+observer+"&password="+secret;
                var login=client.send(HttpRequest.newBuilder(host.resolve("/api/v1/access/login")).header("Content-Type","application/x-www-form-urlencoded").header(csrf.get("headerName").stringValue(),csrf.get("token").stringValue()).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
                assertThat(login.statusCode()).isEqualTo(204);
                var read=client.send(HttpRequest.newBuilder(host.resolve("/api/v1/incidents")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(read.statusCode()).isEqualTo(200);
                result.put("manualReadStatus",read.statusCode()); result.put("modelCredentialsPresent",false);
                assertThat(app.getEnvironment().getProperty("ordivane.ingestion.enabled")).isEqualTo("false");
            }
            try(ConfigurableApplicationContext app=start(root,url,schema,observer,secret,false)) {
                assertThat(app.getBean(ConfigurationLoader.Loaded.class).digest()).isEqualTo(digest);
                assertThat(isolated.queryForObject("SELECT count(*) FROM configuration_version",Integer.class)).isEqualTo(1);
            }
            var store=new ConfigurationVersionStore(isolated,new TransactionTemplate(new JdbcTransactionManager(isolatedData)));
            var env=new HashMap<>(System.getenv()); env.put("ORDIVANE_DEMO_API_TOKEN",UUID.randomUUID().toString());
            assertThat(store.register(new ConfigurationLoader().load(root,env))).isEqualTo(digest);
            Files.writeString(root.resolve("prompts/membership-diagnosis.md"),"\n测试版本变化\n",StandardOpenOption.APPEND);
            String second=store.register(new ConfigurationLoader().load(root,env)); assertThat(second).isNotEqualTo(digest);
            assertThat(isolated.queryForObject("SELECT count(*) FROM configuration_version",Integer.class)).isEqualTo(2);
            for(String sql:List.of("UPDATE configuration_version SET snapshot=snapshot","DELETE FROM configuration_version","TRUNCATE configuration_version")) assertThatThrownBy(()->isolated.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            result.put("versions",List.of(digest,second)); result.put("immutability","UPDATE/DELETE/TRUNCATE rejected");
            String valid=Files.readString(root.resolve("ordivane.yml"));
            Files.writeString(root.resolve("ordivane.yml"),valid.replace("forbid_self_approval: true","forbid_self_approval: false"));
            Throwable failure=org.assertj.core.api.ThrowableAssert.catchThrowable(()-> { try(var ignored=start(root,url,schema,observer,secret,false)) { throw new AssertionError("invalid startup accepted"); } });
            assertThat(failure).isNotNull();
            Throwable cause=failure; while(cause.getCause()!=null) cause=cause.getCause();
            assertThat(cause).isInstanceOf(ConfigurationException.class).hasMessageContaining("/safety/forbid_self_approval");
            result.put("invalidStartup",cause.getMessage());
            result.put("migrationHistory",isolated.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"));
            result.put("snapshotRows",isolated.queryForList("SELECT digest,snapshot FROM configuration_version ORDER BY created_at"));
            completed=true;
        } finally {
            // 只在清单记录且所有者/数据库/schema均复核后定向清理本任务资源。
            assertThat(admin.queryForObject("SELECT current_database()",String.class)).isEqualTo("ordivane_platform");
            assertThat(schema).matches("be040_[a-f0-9]{12}");
            assertThat(admin.queryForObject("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname=?",String.class,schema)).isEqualTo("ordivane_platform");
            admin.execute("DROP SCHEMA "+schema+" CASCADE");
            result.put("schemaRemaining",admin.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?",Integer.class,schema));
            result.put("completed",completed); result.put("contextsStopped",true);
            Files.writeString(evidence.resolve("runtime-"+run+"-results.json"),json.writeValueAsString(result));
        }
    }

    private ConfigurableApplicationContext start(Path root,String url,String schema,String observer,String secret,boolean initialize) {
        List<String> args=new ArrayList<>(List.of("--spring.profiles.active=local","--server.port=0","--spring.datasource.url="+url,"--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.flyway.create-schemas=false","--ordivane.configuration.root="+root,"--ordivane.ingestion.enabled=false","--ordivane.access.initialize="+initialize,"--logging.level.root=WARN"));
        // 密码通过内存PropertySource传递，不能放命令行/日志。
        Map<String,Object> properties=new HashMap<>();
        for(String role:List.of("OBSERVER","OPERATOR","APPROVER")) {
            properties.put("ORDIVANE_ACCESS_"+role+"_USERNAME",role.equals("OBSERVER")?observer:observer+"."+role);
            properties.put("ORDIVANE_ACCESS_"+role+"_PASSWORD",secret);
        }
        properties.put("ORDIVANE_ACCESS_SOURCE_ID",observer); properties.put("ORDIVANE_ACCESS_SOURCE_CREDENTIAL",secret);
        return new SpringApplicationBuilder(PlatformApplication.class).properties(properties).run(args.toArray(String[]::new));
    }
}
