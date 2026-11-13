package io.ordivane.platform.ingestion;

import io.ordivane.platform.incident.IncidentService;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 真实8080平台、8081演示子进程、PG/MQ闭环；无接收桩，不直接插入失败报告。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncidentIntegrationTests {
    private static final String RUN="BE-030-"+UUID.randomUUID();
    private static final String PREFIX="ordivane.test.be030."+RUN.toLowerCase(Locale.ROOT);
    private static final String SOURCE="be030."+UUID.randomUUID();
    private static final String SECRET=UUID.randomUUID().toString()+UUID.randomUUID();
    private static final String TOKEN=UUID.randomUUID().toString();
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String QUEUE=PREFIX+".membership.failed";
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<String> orders=new ArrayList<>();
    private final List<String> traces=new CopyOnWriteArrayList<>();
    private final List<Map<String,Object>> results=new ArrayList<>();
    private final Path output=Path.of("../workflow/backend/tasks/BE-030/evidence");
    private Process demo;
    private JdbcTemplate demoDb;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.rabbit.connection.ConnectionFactory connection;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired IngestionStore store;
    @Autowired ReportDecoder decoder;
    @Autowired IncidentService incidents;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("server.port",()->8080);
        p.add("ordivane.ingestion.enabled",()->true);p.add("ordivane.ingestion.queue",()->QUEUE);
        p.add("ordivane.ingestion.source",()->SOURCE);p.add("ordivane.membership.token",()->TOKEN);
        p.add("ordivane.access.initialize",()->true);
        for(String role:List.of("OBSERVER","OPERATOR","APPROVER")) {
            p.add("ORDIVANE_ACCESS_"+role+"_USERNAME",()->RUN+"."+role);
            p.add("ORDIVANE_ACCESS_"+role+"_PASSWORD",()->PASSWORD);
        }
        p.add("ORDIVANE_ACCESS_SOURCE_ID",()->SOURCE);p.add("ORDIVANE_ACCESS_SOURCE_CREDENTIAL",()->SECRET);
        // 监听器启动前声明本任务队列，不接触开发拓扑。
        p.add("spring.rabbitmq.listener.simple.missing-queues-fatal",()->false);
    }
    @BeforeAll
    void startOwnedDemo() throws Exception {
        System.out.println("BE030 run="+RUN+" source="+SOURCE+" topology="+PREFIX+" platformPid="+ProcessHandle.current().pid());
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",
                "../ordivane-demo/target/ordivane-demo-0.1.0-SNAPSHOT.jar",
                "--server.port=8081","--demo.messaging.prefix="+PREFIX,"--demo.outbox.enabled=false",
                "--demo.reports.poll-ms=200","--demo.reports.retry-delay-ms=100","--spring.profiles.active=local",
                "--demo.simulation.enabled=true");
        builder.environment().put("ORDIVANE_DEMO_API_TOKEN",TOKEN);
        builder.environment().put("ORDIVANE_REPORT_ENABLED","true");
        builder.environment().put("ORDIVANE_REPORT_ENDPOINT","http://127.0.0.1:8080/api/v1/failure-reports");
        builder.environment().put("ORDIVANE_REPORT_SOURCE_ID",SOURCE);
        builder.environment().put("ORDIVANE_REPORT_SOURCE_CREDENTIAL",SECRET);
        builder.redirectErrorStream(true).redirectOutput(output.resolve(RUN+"-demo.log").toFile());
        demo=builder.start();
        demoDb=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://"+System.getenv().getOrDefault("ORDIVANE_PG_HOST","127.0.0.1")
                +":"+System.getenv().getOrDefault("ORDIVANE_PG_PORT","5432")+"/ordivane_demo","ordivane_demo",System.getenv("ORDIVANE_DEMO_DB_PASSWORD")));
        await(()-> { try { return request(http,8081,"GET","/actuator/health",null,null,null).statusCode()==200; } catch(Exception e) { return false; } },"demo ready");
        await(()->registry.getListenerContainer("failure-ingestion").isRunning(),"platform listener ready");
        System.out.println("BE030 demoPid="+demo.pid());
    }
    @Test
    @Order(1)
    void realSenderAuthenticationTransactionsCasesAndEvidence() throws Exception {
        UUID event=paid("downstream");
        fault(event,"DOWNSTREAM_UNAVAILABLE");publish(event);publish(event);
        await(()->count("failure_observation","source",SOURCE)>=2,"real sender to authenticated platform");
        await(()->demoDb.queryForObject("SELECT count(*) FROM failure_report WHERE topology_prefix=? AND http_status='SENT' AND dead_status='SENT'",Long.class,PREFIX)==2,"both sender channels acknowledged");
        assertThat(count("incident","source",SOURCE)).isEqualTo(1);
        assertThat(count("failure_observation","source",SOURCE)).isEqualTo(2);
        await(()->jdbc.queryForObject("SELECT count(*) FROM audit_record WHERE subject_id=? AND action='DLQ_ACCEPT' AND result='SUCCESS'",Long.class,SOURCE)>=2,"MQ receive commit and audit, distinct from sender broker confirm");
        String body=jdbc.queryForObject("SELECT body FROM failure_observation WHERE source=? ORDER BY received_at LIMIT 1",String.class,SOURCE);
        JsonNode report=json.readTree(body);
        UUID incident=jdbc.queryForObject("SELECT id FROM incident WHERE source=? AND event_id=?",UUID.class,SOURCE,event);
        assertThat(sourcePost(body,SECRET).statusCode()).isEqualTo(200);
        var changed=json.readTree(body).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)changed).put("bodySha256","0".repeat(64));
        assertThat(sourcePost(json.writeValueAsString(changed),SECRET).statusCode()).isEqualTo(409);
        assertThat(sourcePost(body,TOKEN).statusCode()).isEqualTo(401);
        assertThat(sourcePost(body.replace(SOURCE,"other-source"),SECRET).statusCode()).isEqualTo(403);
        assertThat(request(http,8080,"GET","/api/v1/incidents",null,SECRET,null).statusCode()).isEqualTo(401);
        assertThat(request(http,8080,"POST","/api/v1/failure-reports","{}",null,null).statusCode()).isEqualTo(401);
        var op=login("OPERATOR");var observer=login("OBSERVER");var approver=login("APPROVER");
        var listed=request(op,8080,"GET","/api/v1/incidents?source="+SOURCE+"&status=OPEN&size=1&page=0",null,null,null);
        assertThat(listed.statusCode()).isEqualTo(200);assertThat(json.readTree(listed.body()).get("total").asLong()).isEqualTo(1);
        assertThat(request(op,8080,"GET","/api/v1/incidents?size=0",null,null,null).statusCode()).isEqualTo(400);
        assertThat(request(op,8080,"GET","/api/v1/incidents/"+UUID.randomUUID(),null,null,null).statusCode()).isEqualTo(404);
        String path="/api/v1/incidents/"+incident;
        for(var c:List.of(op,observer,approver)) { assertThat(request(c,8080,"GET",path,null,null,null).statusCode()).isEqualTo(200); }
        assertThat(request(op,8080,"POST","/api/v1/failure-reports",body,null,null).statusCode()).isEqualTo(401);
        assertThat(request(op,8080,"POST",path+"/evidence-collections",null,null,null).statusCode()).isEqualTo(403);
        assertThat(collect(observer,path,null).statusCode()).isEqualTo(403);
        assertThat(collect(approver,path,null).statusCode()).isEqualTo(403);
        assertThat(collect(op,path,"{\"evidenceId\":\""+UUID.randomUUID()+"\"}").statusCode()).isEqualTo(400);
        var first=collect(op,path,null);assertThat(first.statusCode()).isEqualTo(200);
        results.add(Map.of("case","http-api-snapshots","list",json.readTree(listed.body()),
                "detail",json.readTree(request(op,8080,"GET",path,null,null,null).body()),
                "evidence",json.readTree(request(op,8080,"GET",path+"/evidence",null,null,null).body()),"collection",json.readTree(first.body())));
        UUID evidenceId=UUID.fromString(json.readTree(first.body()).get("evidenceId").asString());
        assertThat(jdbc.queryForObject("SELECT content FROM evidence_snapshot WHERE id=?",String.class,evidenceId)).contains("\"businessVerified\":false","\"active\":false");
        assertThatThrownBy(()->jdbc.update("UPDATE incident_event SET body='{}' WHERE source=? AND event_id=?",SOURCE,event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE evidence_snapshot SET content='{}' WHERE id=?",evidenceId)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->incidents.requireEvidence(UUID.randomUUID(),evidenceId)).isInstanceOf(IngestionException.class);
        UUID execution=UUID.randomUUID();
        sendRecovery(report.get("originalEvent"),execution);
        await(()->jdbc.queryForObject("SELECT count(*) FROM failure_observation WHERE source=? AND body LIKE ?",Long.class,SOURCE,"%"+execution+"%")>=1,"real recovery failure observation preserves execution ID");
        // 真实故障复位后同事件正常开通，重新采证新增快照，不覆盖首份active=false。
        assertThat(request(http,8081,"DELETE","/api/v1/simulations/faults/"+event,null,TOKEN,null).statusCode()).isEqualTo(200);
        sendRecovery(report.get("originalEvent"),execution);
        await(()-> { try { return json.readTree(request(http,8081,"GET","/api/v1/orders/"+orders.getFirst()+"/entitlements/MEMBERSHIP",null,TOKEN,null).body()).get("active").asBoolean(); } catch(Exception e){ return false; } },"actual entitlement active");
        assertThat(collect(op,path,null).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT content FROM evidence_snapshot WHERE incident_id=? ORDER BY collected_at",String.class,incident)).hasSize(2).anyMatch(s->s.contains("\"businessVerified\":true"));
        assertThat(jdbc.queryForObject("SELECT content FROM evidence_snapshot WHERE id=?",String.class,evidenceId)).contains("\"active\":false");
        // 同一报告并发首次：真实HTTP202仅一份，其他200；独立观察与事件冲突均保留。
        var concurrent=newReport(report);String concurrentBody=json.writeValueAsString(concurrent);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> pending=new ArrayList<>();
            for(int i=0;i<6;i++) { pending.add(pool.submit(()->sourcePost(concurrentBody,SECRET).statusCode())); }
            List<Integer> codes=new ArrayList<>();for(var future:pending) { codes.add(future.get(10,TimeUnit.SECONDS)); }
            assertThat(codes).containsOnly(200,202);assertThat(Collections.frequency(codes,202)).isEqualTo(1);
        }
        var conflictingEvent=newReport(report);
        ((tools.jackson.databind.node.ObjectNode)conflictingEvent.get("originalEvent").get("payload")).put("orderId",RUN+"-different");
        assertThat(sourcePost(json.writeValueAsString(conflictingEvent),SECRET).statusCode()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM failure_observation WHERE source=? AND event_conflict",Long.class,SOURCE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT body FROM incident_event WHERE source=? AND event_id=?",String.class,SOURCE,event)).doesNotContain("different");
        // HTTP先到再MQ、MQ先到再HTTP；相同报告不额外观察。
        var ordered=newReport(report);String orderedBody=json.writeValueAsString(ordered);
        assertThat(sourcePost(orderedBody,SECRET).statusCode()).isEqualTo(202);sendReport(orderedBody);sendReport(orderedBody);
        var reverse=newReport(report);String reverseBody=json.writeValueAsString(reverse);sendReport(reverseBody);
        UUID reverseId=UUID.fromString(reverse.get("reportId").asString());
        await(()->count("failure_observation","report_id",reverseId)==1,"MQ first report committed");
        assertThat(sourcePost(reverseBody,SECRET).statusCode()).isEqualTo(200);
        // null事件仅观察，非法/不可信原文只hash，不保存秘密，也不伪造案件。
        long beforeCases=count("incident","source",SOURCE);
        String nullReport=Files.readString(Path.of("../contracts/events/failure-report-v1.example.json"));
        nullReport=nullReport.replace("ordivane-demo",SOURCE).replace("ordivane.demo.membership.failed",QUEUE);
        var nullNode=newReport(json.readTree(nullReport));
        assertThat(sourcePost(json.writeValueAsString(nullNode),SECRET).statusCode()).isEqualTo(202);
        MessageProperties bad=new MessageProperties();bad.setMessageId(UUID.randomUUID().toString());
        rabbit.send("",QUEUE,new Message(("{\"password\":\"secret-"+RUN+"\"}").getBytes(StandardCharsets.UTF_8),bad));
        var wrongSource=newReport(report);wrongSource.put("source","unregistered-source");sendReport(json.writeValueAsString(wrongSource));
        var wrongHeader=newReport(report);var wrongProperties=new MessageProperties();
        wrongProperties.setHeader("reportId",UUID.randomUUID().toString());
        wrongProperties.setHeader("observationId",wrongHeader.get("observationId").asString());
        rabbit.send("",QUEUE,new Message(json.writeValueAsBytes(wrongHeader),wrongProperties));
        await(()->jdbc.queryForObject("SELECT count(*) FROM failure_observation WHERE source=? AND kind='QUARANTINED'",Long.class,SOURCE)>=1,"safe quarantine persisted");
        assertThat(count("incident","source",SOURCE)).isEqualTo(beforeCases);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM failure_observation WHERE source=? AND body LIKE ?",Long.class,SOURCE,"%secret-"+RUN+"%" )).isZero();
        // 原文白名单兜底（受控传输实验），重复去重，不伪造应用报告ID。
        byte[] raw=json.writeValueAsBytes(report.get("originalEvent"));MessageProperties props=new MessageProperties();props.setMessageId(UUID.randomUUID().toString());
        rabbit.send("",QUEUE,new Message(raw,props));rabbit.send("",QUEUE,new Message(raw,props));
        await(()->jdbc.queryForObject("SELECT count(*) FROM failure_observation WHERE source=? AND kind='RAW_DLX'",Long.class,SOURCE)==1,"raw fallback persisted once");
        await(()->jdbc.queryForObject("SELECT count(*) FROM failure_observation WHERE source=? AND kind='QUARANTINED'",Long.class,SOURCE)>=3,"source/header mismatches quarantined");
        var secondHandler=new IngestionStore(jdbc,new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()),decoder);
        secondHandler.fallback(SOURCE,"membership.other",IngestionStore.hash(RUN+"handler"),raw,report.get("originalEvent"));
        assertThat(count("incident","source",SOURCE)).isEqualTo(beforeCases+1);
        results.add(Map.of("case","real-sender-http-source-transaction-human-evidence-roles-csrf-idempotency-concurrency-immutable-mq-order-null-raw", "result","PASS","incident",incident.toString()));
        // 依赖失败必须可查询且不破坏历史；仅停自有demo PID。
        demo.destroy();assertThat(demo.waitFor(15,TimeUnit.SECONDS)).isTrue();
        assertThat(collect(op,path,null).statusCode()).isEqualTo(503);
        assertThat(request(op,8080,"GET",path+"/evidence",null,null,null).body()).contains("DEPENDENCY_FAILED","COLLECTED");
        results.add(Map.of("case","dependency-failure-visible", "result","PASS"));
    }
    @Test
    @Order(2)
    void deferredCommitFailureStopsWithoutAckAndPreservesBrokerMessage() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");
        String function="be030_commit_"+suffix, trigger="be030_commit_"+suffix;
        byte[] body=("invalid-commit-"+RUN).getBytes(StandardCharsets.UTF_8);
        String hash=ReportDecoder.hash(body);
        // 故障只针对本次source和精确正文hash；约束触发器在COMMIT时失败，不影响其他写者。
        jdbc.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'BE030 controlled commit failure'; END $$");
        try {
            jdbc.execute("CREATE CONSTRAINT TRIGGER "+trigger+" AFTER INSERT ON failure_observation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.source='"+SOURCE+"' AND NEW.body_sha256='"+hash+"') EXECUTE FUNCTION "+function+"()");
            rabbit.send("",QUEUE,new Message(body,new MessageProperties()));
            await(()->!registry.getListenerContainer("failure-ingestion").isRunning(),"commit failure stops listener");
            var admin=new RabbitAdmin(connection);
            await(()-> { var p=admin.getQueueProperties(QUEUE);return p!=null && ((Number)p.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue()==1; },"unacknowledged message returns to broker");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM failure_observation WHERE source=? AND body_sha256=?",Long.class,SOURCE,hash)).isZero();
            // 停止后等待两秒，消息仍一条，未发生自动无限requeue/ACK丢失。
            Thread.sleep(2000);
            assertThat(((Number)admin.getQueueProperties(QUEUE).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue()).isEqualTo(1);
            results.add(Map.of("case","real-deferred-commit-failure-no-ack-no-loop", "result","PASS","bodySha256",hash));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS "+trigger+" ON failure_observation");jdbc.execute("DROP FUNCTION IF EXISTS "+function+"()");
        }
        registry.getListenerContainer("failure-ingestion").start();
        await(()->jdbc.queryForObject("SELECT count(*) FROM failure_observation WHERE source=? AND body_sha256=?",Long.class,SOURCE,hash)==1,"explicit restart commits preserved message");
        results.add(Map.of("case","explicit-restart-after-repair", "result","PASS"));
    }
    private tools.jackson.databind.node.ObjectNode newReport(JsonNode old) {
        var n=(tools.jackson.databind.node.ObjectNode)old.deepCopy();String id=UUID.randomUUID().toString(), observation=UUID.randomUUID().toString();
        n.put("reportId",id);n.put("observationId",observation);
        var dl=(tools.jackson.databind.node.ObjectNode)n.get("deadLetter");dl.put("reportId",id);dl.put("observationId",observation);return n;
    }
    private void sendReport(String body) {
        var n=json.readTree(body);var p=new MessageProperties();p.setContentType("application/json");
        p.setHeader("reportId",n.get("reportId").asString());p.setHeader("observationId",n.get("observationId").asString());
        rabbit.send("",QUEUE,new Message(body.getBytes(StandardCharsets.UTF_8),p));
    }
    /** 测试传输实验，不是平台恢复发布能力；仍走演示既有恢复消费者。 */
    private void sendRecovery(JsonNode event,UUID execution) {
        var properties=new MessageProperties();properties.setContentType("application/json");
        properties.setHeader("recoveryOrigin","ordivane-platform");properties.setHeader("executionId",execution.toString());
        rabbit.send(PREFIX+".recovery","membership.activate",new Message(json.writeValueAsBytes(event),properties));
    }
    private long count(String table,String field,Object value) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+field+"=?",Long.class,value); }
    private UUID paid(String suffix) throws Exception {
        String id=RUN+"-"+suffix;orders.add(id);
        assertThat(request(http,8081,"POST","/api/v1/orders","{\"orderId\":\""+id+"\",\"entitlementType\":\"MEMBERSHIP\"}",TOKEN,null).statusCode()).isEqualTo(200);
        var result=request(http,8081,"POST","/api/v1/orders/"+id+"/payments",null,TOKEN,null);
        assertThat(result.statusCode()).isEqualTo(200);return UUID.fromString(json.readTree(result.body()).get("event_id").asString());
    }
    private void fault(UUID id,String scenario) throws Exception { assertThat(request(http,8081,"PUT","/api/v1/simulations/faults/"+id,"{\"scenario\":\""+scenario+"\"}",TOKEN,null).statusCode()).isEqualTo(200); }
    private void publish(UUID id) throws Exception { assertThat(request(http,8081,"POST","/api/v1/simulations/faults/"+id+"/messages",null,TOKEN,null).statusCode()).isEqualTo(200); }
    private HttpResponse<String> sourcePost(String body,String token) throws Exception { return request(http,8080,"POST","/api/v1/failure-reports",body,token,null); }
    private HttpClient login(String role) throws Exception {
        var cookie=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var c=HttpClient.newBuilder().cookieHandler(cookie).build();
        String csrf=json.readTree(request(c,8080,"GET","/api/v1/access/csrf",null,null,null).body()).get("token").asString();
        String form="username="+URLEncoder.encode(RUN+"."+role,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(PASSWORD,StandardCharsets.UTF_8);
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1/access/login")).header("Content-Type","application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response=c.send(req,HttpResponse.BodyHandlers.ofString());recordTrace(response);assertThat(response.statusCode()).isEqualTo(204);return c;
    }
    private HttpResponse<String> collect(HttpClient c,String path,String body) throws Exception {
        String csrf=json.readTree(request(c,8080,"GET","/api/v1/access/csrf",null,null,null).body()).get("token").asString();
        return request(c,8080,"POST",path+"/evidence-collections",body,null,csrf);
    }
    private HttpResponse<String> request(HttpClient c,int port,String method,String path,String body,String token,String csrf) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(token!=null) { b.header("Authorization","Bearer "+token); }if(csrf!=null) { b.header("X-CSRF-TOKEN",csrf); }
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var result=c.send(b.build(),HttpResponse.BodyHandlers.ofString());if(port==8080) { recordTrace(result); }return result;
    }
    private void recordTrace(HttpResponse<?> response) { response.headers().firstValue("X-Trace-Id").ifPresent(traces::add); }
    private void await(BooleanSupplier condition,String message) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(System.nanoTime()<deadline) { if(condition.getAsBoolean()) { return; } Thread.sleep(100); }
        throw new AssertionError("timeout: "+message);
    }
    @AfterAll
    void cleanupExactOwnedResources() throws Exception {
        registry.getListenerContainer("failure-ingestion").stop();
        if(demo!=null && demo.isAlive()) { demo.destroy(); if(!demo.waitFor(15,TimeUnit.SECONDS)) { demo.destroyForcibly();demo.waitFor(10,TimeUnit.SECONDS); } }
        jdbc.update("DELETE FROM evidence_snapshot WHERE incident_id IN (SELECT id FROM incident WHERE source=?)",SOURCE);
        jdbc.update("DELETE FROM failure_observation WHERE source=?",SOURCE);
        jdbc.update("DELETE FROM incident WHERE source=?",SOURCE);jdbc.update("DELETE FROM incident_event WHERE source=?",SOURCE);
        for(String trace:traces) { jdbc.update("DELETE FROM audit_record WHERE trace_id=?",trace); }
        // 源端真实发送的trace未经过测试HTTP客户端，按本任务source主体精确清理。
        jdbc.update("DELETE FROM audit_record WHERE subject_type='SOURCE' AND subject_id=?",SOURCE);
        jdbc.update("DELETE FROM audit_record WHERE subject_id IN (SELECT id::text FROM access_user WHERE username IN (?,?,?))",RUN+".OBSERVER",RUN+".OPERATOR",RUN+".APPROVER");
        jdbc.update("DELETE FROM access_user WHERE username IN (?,?,?)",RUN+".OBSERVER",RUN+".OPERATOR",RUN+".APPROVER");
        jdbc.update("DELETE FROM access_source WHERE source_id=?",SOURCE);
        if(demoDb!=null) {
            demoDb.update("DELETE FROM failure_report WHERE topology_prefix=?",PREFIX);
            for(String order:orders) {
                for(String table:List.of("membership_entitlement","processing_record","outbox_event")) { demoDb.update("DELETE FROM "+table+" WHERE order_id=?",order); }
                demoDb.update("DELETE FROM orders WHERE order_id=?",order);
            }
        }
        var admin=new RabbitAdmin(connection);
        for(String q:List.of(".membership.original",".membership.recovery",".membership.failed")) { admin.deleteQueue(PREFIX+q); }
        for(String e:List.of(".events",".recovery",".dead")) { admin.deleteExchange(PREFIX+e); }
        assertThat(count("incident","source",SOURCE)).isZero();assertThat(count("failure_observation","source",SOURCE)).isZero();
        results.add(Map.of("case","cleanup", "result","PASS","source",SOURCE,"topology",PREFIX,"demoPid",demo==null?0:demo.pid(),"demoExited",demo==null||!demo.isAlive()));
        Files.writeString(output.resolve(RUN+"-results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        System.out.println("BE030 cleanup=PASS run="+RUN);
    }
}
