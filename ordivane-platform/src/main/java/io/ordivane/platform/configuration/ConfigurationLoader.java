package io.ordivane.platform.configuration;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static io.ordivane.platform.configuration.SafeConfigurationFiles.error;

/** 启动时加载一次：结构、引用和业务安全底线校验后生成非秘密不可变版本。 */
public final class ConfigurationLoader {
    /** 服务端固定识别集合；选择声明不能动态装配执行实现。 */
    private static final Set<String> TOOLS = Set.of("event-details", "processing-history", "order-status", "entitlement-status", "runbook-search");
    private static final Set<String> CHECKS = Set.of("event-schema-valid", "order-paid-not-refunded", "entitlement-absent", "no-conflicting-execution");
    private static final Set<String> ACTIONS = Set.of("REPLAY_ORIGINAL", "VERIFY_ONLY", "WAIT_AND_RECHECK", "MANUAL_INTERVENTION");
    /** 字段与变量一一绑定，避免把数据库/其他能力凭据导入非秘密字段。 */
    private static final Map<String,String> ENV_FIELDS = Map.of("/connections/rabbitmq/uri","ORDIVANE_RABBITMQ_URI", "/connections/membership/base_url","ORDIVANE_DEMO_BASE_URL", "/connections/membership/api_token","ORDIVANE_DEMO_API_TOKEN", "/model/base_url","ORDIVANE_MODEL_BASE_URL", "/model/model_name","ORDIVANE_MODEL_NAME", "/model/api_key","ORDIVANE_MODEL_API_KEY");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");
    private final JsonMapper json = JsonMapper.builder().enable(tools.jackson.databind.cfg.JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    /** 已知ID仅表示服务器识别；kind为能力组，status为DECLARED，executable恒false。 */
    public record Capability(String id, String kind, String status, boolean executable) { }
    /** source为可信报告身份，不能用OrderPaid.source事件产生者替代。 */
    public record Match(String source, String eventType, int schemaVersion, String handler) { }
    /** digest为快照UTF-8摘要，snapshot为非秘密规范JSON；能力与匹配表均不可变。 */
    public record Loaded(String digest, String snapshot, List<Capability> capabilities, Map<Match,String> matches, String sourceId) {
        public Loaded { capabilities = List.copyOf(capabilities); matches = Map.copyOf(matches); }
        /** 精确匹配，无命中保留人工入口，不猜测业务处理器。 */
        public Optional<String> match(String source, String eventType, int version, String handler) {
            return Optional.ofNullable(matches.get(new Match(source,eventType,version,handler)));
        }
    }

    /** env由调用者显式提供，不读取.env、不执行表达式；关闭模型时忽略其连接与凭据。 */
    public Loaded load(Path root, Map<String,String> env) {
        SafeConfigurationFiles files = new SafeConfigurationFiles(root,json);
        ObjectNode main = object(files.yaml(files.resolve("ordivane.yml","ordivane.yml","/"),"ordivane.yml"),"ordivane.yml");
        validate(files, main, "schemas/ordivane.schema.json", "ordivane.yml");
        boolean model = main.at("/model/enabled").booleanValue();
        resolveEnvironment(main,env,model,"ordivane.yml");
        validate(files, main, "schemas/ordivane.schema.json", "ordivane.yml");
        validateConnections(main, model);
        validateRegistry(main);
        Path directory = files.resolve(main.get("scenario_directory").stringValue(),"ordivane.yml","/scenario_directory");
        List<Path> paths;
        try (var entries = Files.list(directory)) {
            paths = entries.limit(33).sorted().toList();
            if (paths.isEmpty() || paths.size()>32) throw error("ordivane.yml","/scenario_directory","场景数量必须为1至32");
        } catch (ConfigurationException e) { throw e; }
        catch (Exception e) { throw error("ordivane.yml","/scenario_directory","场景目录不可读"); }
        Map<Match,String> matches = new HashMap<>(); Set<String> ids = new HashSet<>();
        ObjectNode scenarios = json.createObjectNode(), texts = json.createObjectNode();
        for (int index=0; index<paths.size(); index++) {
            Path path = paths.get(index);
            // 错误文件名来自序号，不回显不可信文件名。
            String file = "scenarios["+index+"]";
            String name = path.getFileName().toString();
            if (!(name.endsWith(".yml") || name.endsWith(".yaml"))) throw error(file,"/","场景目录仅允许YAML文件");
            String relative = files.relative(path);
            JsonNode scenario = files.yaml(files.resolve(relative,file,"/"),file);
            validate(files, scenario,"schemas/scenario.schema.json",file);
            resolveEnvironment(scenario,env,false,file);
            JsonNode s = scenario.get("scenario"); String id = s.get("id").stringValue();
            if (!ids.add(id)) throw error(file,"/scenario/id","场景ID重复");
            JsonNode match = s.get("match");
            if (!match.get("source").stringValue().equals(main.at("/connections/rabbitmq/source_id").stringValue())) throw error(file,"/scenario/match/source","匹配来源必须是配置的可信报告身份");
            if (!match.get("handler").stringValue().equals("membership.activate")) throw error(file,"/scenario/match/handler","处理器不属于已接受接入契约");
            Match key = new Match(match.get("source").stringValue(),match.get("event_type").stringValue(),match.get("schema_version").intValue(),match.get("handler").stringValue());
            if (matches.putIfAbsent(key,id)!=null) throw error(file,"/scenario/match","场景匹配歧义");
            validateScenario(main,s,model,file);
            addText(files,texts,s.at("/diagnosis/prompt").stringValue(),file,"/scenario/diagnosis/prompt");
            for (JsonNode runbook : s.at("/diagnosis/runbooks")) addText(files,texts,runbook.stringValue(),file,"/scenario/diagnosis/runbooks");
            scenarios.set(id,scenario);
        }
        redact(main,model);
        List<Capability> capabilities = capabilities(main);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("snapshot_version",1); snapshot.set("configuration",main); snapshot.set("scenarios",scenarios); snapshot.set("texts",texts);
        snapshot.set("capabilities",json.valueToTree(capabilities));
        String canonical = json.writeValueAsString(snapshot);
        if (canonical.getBytes(StandardCharsets.UTF_8).length>4*1024*1024) throw error("ordivane.yml","/","快照超过4MiB");
        return new Loaded(sha256(canonical),canonical,capabilities,matches,main.at("/connections/rabbitmq/source_id").stringValue());
    }

    /** 在首次结构校验前拒绝非对象根。 */
    private ObjectNode object(JsonNode node,String file) {
        if (!(node instanceof ObjectNode object)) throw error(file,"/","根必须是对象");
        return object;
    }

    /** 使用真实2020-12验证器；错误仅输出实例位置和关键字，不输出库的值消息。 */
    private void validate(SafeConfigurationFiles files, JsonNode value, String schemaPath, String file) {
        try {
            JsonNode schema = json.readTree(files.text(files.resolve(schemaPath,file,"/schema"),file,"/schema"));
            // 只加载随部署工作根提供的两个本地自含Schema，禁止外部引用和替换方言。
            if (!schema.path("$schema").asString("").equals("https://json-schema.org/draft/2020-12/schema")) throw error(file,"/schema","不支持的Schema方言");
            Deque<JsonNode> nodes = new ArrayDeque<>(); nodes.add(schema);
            while (!nodes.isEmpty()) {
                JsonNode node = nodes.removeFirst();
                if (node.isObject() && (node.has("$ref") || node.has("$dynamicRef") || node.has("$id"))) throw error(file,"/schema","Schema必须自含且不得加载外部资源");
                if (node.isContainer()) node.forEach(nodes::addLast);
            }
            var errors = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema).validate(value);
            if (!errors.isEmpty()) {
                var failure = errors.getFirst();
                String location = failure.getInstanceLocation().toString();
                // 不从不可信输入字段名生成日志；只保留Schema声明过的字段位置。
                String safe = location.replace("$", "").replace(".", "/");
                Set<String> fields = new HashSet<>();
                Deque<JsonNode> definitions = new ArrayDeque<>(); definitions.add(schema);
                while (!definitions.isEmpty()) {
                    JsonNode definition = definitions.removeFirst();
                    if (definition.isObject() && definition.has("properties")) definition.get("properties").properties().forEach(p -> fields.add(p.getKey()));
                    if (definition.isContainer()) definition.forEach(definitions::addLast);
                }
                StringBuilder trusted = new StringBuilder();
                for (String part : safe.split("/")) {
                    if (part.isEmpty()) continue;
                    if (!fields.contains(part) && !part.matches("[0-9]+")) break;
                    trusted.append('/').append(part);
                }
                safe = trusted.toString();
                throw error(file,safe.isEmpty()?"/":safe,"Schema规则不满足("+failure.getKeyword()+")");
            }
        } catch (ConfigurationException e) { throw e; }
        catch (Exception e) { throw error(file,"/schema","Schema加载或校验失败"); }
    }

    /** 迭代遍历以约束替换面；占位符必须占满字段，不支持拼接/默认表达式。 */
    private void resolveEnvironment(JsonNode value, Map<String,String> env, boolean model, String file) {
        record Visit(JsonNode node,String pointer) { }
        Deque<Visit> queue = new ArrayDeque<>(); queue.add(new Visit(value,""));
        while(!queue.isEmpty()) {
            Visit visit = queue.removeFirst(); JsonNode node=visit.node();
            if (node.isObject()) for (var property : node.properties()) {
                String pointer=visit.pointer()+"/"+property.getKey(); JsonNode child=property.getValue();
                if (child.isString()) {
                    String text=child.stringValue();
                    if (pointer.startsWith("/model/") && !model && Set.of("/model/base_url","/model/model_name","/model/api_key").contains(pointer)) continue;
                    if (text.contains("${") || text.contains("$(")) {
                        var matcher=VARIABLE.matcher(text);
                        if (!file.equals("ordivane.yml") || !matcher.matches() || !matcher.group(1).equals(ENV_FIELDS.get(pointer))) throw error(file,pointer,"不允许此环境表达式或跨字段变量引用");
                        String resolved=env.get(matcher.group(1));
                        if (resolved==null || resolved.isBlank()) throw error(file,pointer,"缺少必需环境变量");
                        ((ObjectNode)node).put(property.getKey(),resolved);
                    }
                } else queue.add(new Visit(child,pointer));
            }
            else if(node.isArray()) for(int i=0;i<node.size();i++) {
                JsonNode child=node.get(i);
                if(child.isString() && (child.stringValue().contains("${") || child.stringValue().contains("$("))) throw error(file,visit.pointer(),"数组中不允许环境表达式");
                queue.add(new Visit(child,visit.pointer()+"/"+i));
            }
        }
    }

    /** 验证连接安全策略及已接受C01接入身份/队列边界。 */
    private void validateConnections(JsonNode main, boolean model) {
        String file="ordivane.yml";
        uri(main.at("/connections/rabbitmq/uri").stringValue(),true,file,"/connections/rabbitmq/uri");
        uri(main.at("/connections/membership/base_url").stringValue(),false,file,"/connections/membership/base_url");
        if(model) uri(main.at("/model/base_url").stringValue(),false,file,"/model/base_url");
        if (!main.at("/connections/rabbitmq/dead_letter_queue").stringValue().equals("ordivane.demo.membership.failed")) throw error(file,"/connections/rabbitmq/dead_letter_queue","失败队列必须对应已接受传输契约");
        String source=main.at("/connections/rabbitmq/source_id").stringValue();
        if(!source.matches("[A-Za-z0-9_.-]{1,128}") || source.equals("ordivane-demo")) throw error(file,"/connections/rabbitmq/source_id","需要独立可信报告身份，不能使用事件产生者");
    }

    /** HTTP仅允许loopback，其他连接必须HTTPS；禁止用户信息、查询、片段和重定向配置。 */
    private URI uri(String value, boolean rabbit, String file, String field) {
        try {
            URI uri=URI.create(value); String scheme=uri.getScheme(); String host=uri.getHost();
            if(host==null || uri.getRawQuery()!=null || uri.getRawFragment()!=null) throw new IllegalArgumentException();
            if(rabbit) { if(!Set.of("amqp","amqps").contains(scheme)) throw new IllegalArgumentException(); }
            else if(uri.getRawUserInfo()!=null || !("https".equals(scheme) || "http".equals(scheme) && Set.of("localhost","127.0.0.1","[::1]").contains(host))) throw new IllegalArgumentException();
            return uri;
        } catch(Exception e) { throw error(file,field,"连接地址不满足服务器协议/主机安全规则"); }
    }

    /** 服务器底线独立于可选择能力，目标传输不能被任意字符串覆盖。 */
    private void validateRegistry(JsonNode main) {
        for(String field:List.of("replay_approval_required","forbid_self_approval","preserve_original_payload","recheck_before_execution")) {
            JsonNode rule=main.at("/safety/"+field);
            if(!rule.isBoolean() || !rule.booleanValue()) throw error("ordivane.yml","/safety/"+field,"服务器安全底线不能关闭");
        }
        references(main.at("/registry/tools"),TOOLS,"ordivane.yml","/registry/tools");
        references(main.at("/registry/checks"),CHECKS,"ordivane.yml","/registry/checks");
        references(main.at("/registry/verifiers"),Set.of("membership-activated"),"ordivane.yml","/registry/verifiers");
        Set<String> ids=new HashSet<>();
        for(JsonNode target:main.at("/registry/replay_targets")) {
            if(!target.get("id").stringValue().equals("membership-replay") || !ids.add(target.get("id").stringValue())) throw error("ordivane.yml","/registry/replay_targets/id","目标未知或重复");
            for(var entry:Map.of("exchange","ordivane.demo.recovery","routing_key","membership.activate","queue","ordivane.demo.membership.recovery").entrySet())
                if(!target.path(entry.getKey()).asString("").equals(entry.getValue())) throw error("ordivane.yml","/registry/replay_targets/"+entry.getKey(),"目标必须对应已接受传输契约");
        }
    }

    /** 校验场景引用、他人审批和预算关系；不执行任何恢复检查。 */
    private void validateScenario(JsonNode main, JsonNode s, boolean model, String file) {
        references(s.get("evidence_tools"),strings(main.at("/registry/tools")),file,"/scenario/evidence_tools");
        references(s.at("/diagnosis/tools"),strings(s.get("evidence_tools")),file,"/scenario/diagnosis/tools");
        references(s.at("/recovery/replay_prechecks"),strings(main.at("/registry/checks")),file,"/scenario/recovery/replay_prechecks");
        references(json.createArrayNode().add(s.at("/verification/verifier")),strings(main.at("/registry/verifiers")),file,"/scenario/verification/verifier");
        if(!s.at("/recovery/replay_target").stringValue().equals("membership-replay")) throw error(file,"/scenario/recovery/replay_target","未注册目标");
        Set<String> allowed=strings(s.at("/recovery/allowed_actions"));
        references(s.at("/recovery/allowed_actions"),ACTIONS,file,"/scenario/recovery/allowed_actions");
        if(!s.at("/recovery/approval/forbid_self_approval").isBoolean() || !s.at("/recovery/approval/forbid_self_approval").booleanValue()) throw error(file,"/scenario/recovery/approval/forbid_self_approval","禁止自审不能关闭");
        if(!strings(s.at("/recovery/approval/required_for")).containsAll(allowed)) throw error(file,"/scenario/recovery/approval/required_for","全部允许动作必须经他人审批");
        if(allowed.contains("REPLAY_ORIGINAL") && !strings(s.at("/recovery/replay_prechecks")).containsAll(CHECKS)) throw error(file,"/scenario/recovery/replay_prechecks","重放必须保留全部服务器前置检查");
        if(s.at("/verification/interval_seconds").intValue()>s.at("/verification/timeout_seconds").intValue()) throw error(file,"/scenario/verification/interval_seconds","轮询间隔不能大于验证期限");
        if(model && s.at("/diagnosis/timeout_seconds").intValue()<main.at("/model/request_timeout_seconds").intValue()) throw error(file,"/scenario/diagnosis/timeout_seconds","诊断预算小于单次模型期限");
    }

    /** 结构校验后把ID数组作为集合比较，保留原快照中的数组顺序。 */
    private Set<String> strings(JsonNode array) { Set<String> values=new HashSet<>(); array.forEach(v->values.add(v.stringValue())); return values; }
    /** 引用必须属于对应配置集合且已被服务器识别。 */
    private void references(JsonNode array,Set<String> known,String file,String field) {
        if(!known.containsAll(strings(array))) throw error(file,field,"引用未登记服务器能力");
    }
    /** 同一文本只加载一次，内容逐字进入业务版本，不赋予文本执行权。 */
    private void addText(SafeConfigurationFiles files,ObjectNode texts,String path,String file,String field) {
        String directory=field.endsWith("/prompt")?"prompts/":"runbooks/";
        if(!path.startsWith(directory) || !path.endsWith(".md")) throw error(file,field,"文本仅允许对应prompts/runbooks目录内的Markdown资源");
        if(texts.has(path)) return;
        String content=files.text(files.resolve(path,file,field),file,field);
        texts.put(path,content);
    }
    /** 移除凭据和禁用能力的无效连接，非秘密连接地址继续参与摘要。 */
    private void redact(ObjectNode main, boolean model) {
        ObjectNode rabbit=(ObjectNode)main.at("/connections/rabbitmq");
        URI uri=URI.create(rabbit.get("uri").stringValue());
        // 删除完整userinfo（用户名也视作秘密），不做秘密摘要，凭据轮换不影响版本。
        String address=uri.getScheme()+"://"+uri.getHost()+(uri.getPort()==-1?"":":"+uri.getPort())+(uri.getRawPath()==null?"":uri.getRawPath());
        rabbit.put("uri",address);
        ((ObjectNode)main.at("/connections/membership")).remove("api_token");
        ObjectNode m=(ObjectNode)main.get("model"); m.remove("api_key");
        if(!model) { m.remove("base_url"); m.remove("model_name"); }
    }
    /** 返回服务器识别的声明状态，不能作为Worker或Agent执行许可。 */
    private List<Capability> capabilities(JsonNode main) {
        List<Capability> result=new ArrayList<>();
        for(String group:List.of("tools","checks","verifiers")) for(JsonNode id:main.at("/registry/"+group)) result.add(new Capability(id.stringValue(),group,"DECLARED",false));
        result.add(new Capability("membership-replay","replay_targets","DECLARED",false));
        for(String action:new TreeSet<>(ACTIONS)) result.add(new Capability(action,"actions","DECLARED",false));
        result.sort(Comparator.comparing(Capability::kind).thenComparing(Capability::id));
        return List.copyOf(result);
    }
    /** UTF-8规范JSON的SHA-256，不包含凭据或凭据摘要。 */
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException("运行时缺少SHA-256",e); }
    }
}
