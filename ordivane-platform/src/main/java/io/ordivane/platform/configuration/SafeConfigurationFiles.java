package io.ordivane.platform.configuration;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.*;
import org.yaml.snakeyaml.nodes.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

/** 限量、严格UTF-8读取；路径必须为工作根内无链接的相对路径。 */
final class SafeConfigurationFiles {
    static final int MAX_BYTES = 262144;
    private final Path root;
    private final JsonMapper json;
    /** 单次加载累计输入最多4MiB，包含Schema、场景和文本。 */
    private int totalBytes;

    /** 工作根先解析为真实目录；此对象仅用于一次启动加载。 */
    SafeConfigurationFiles(Path root, JsonMapper json) {
        this.json = json;
        try { this.root = root.toRealPath(); }
        catch (Exception e) { throw error("ordivane.yml", "/", "工作根不可读"); }
    }

    /** 禁止绝对路径、任意穿越和路径段中的符号链接/Windows重解析点。 */
    Path resolve(String value, String file, String field) {
        try {
            if (value.isBlank() || value.contains("\\") || value.contains(":") || value.startsWith("/")) throw new IllegalArgumentException();
            Path relative = Path.of(value);
            if (relative.isAbsolute()) throw new IllegalArgumentException();
            Path current = root;
            for (Path part : relative) {
                if (part.toString().equals("..") || part.toString().equals(".")) throw new IllegalArgumentException();
                current = current.resolve(part);
                var attributes = Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IllegalArgumentException();
                if (!current.toRealPath().startsWith(root)) throw new IllegalArgumentException();
            }
            return current;
        } catch (Exception e) { throw error(file, field, "路径必须指向工作根内现有无链接资源"); }
    }

    /** 使用同一真实工作根生成相对路径，兼容Windows短文件名根目录。 */
    String relative(Path path) { return root.relativize(path).toString().replace('\\','/'); }

    /** 每个文件最多256KiB；流读取同样有界，避免仅依赖读取前大小。 */
    String text(Path path, String file, String field) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw error(file, field, "文件超过256KiB");
            totalBytes += bytes.length;
            if (totalBytes > 4*1024*1024) throw error(file, field, "累计输入超过4MiB");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (ConfigurationException e) { throw e; }
        catch (Exception e) { throw error(file, field, "文件不可读或UTF-8无效"); }
    }

    /** 拒绝所有锚点/别名/合并键，自定义标签和非JSON标量；不实例化任意Java对象。 */
    JsonNode yaml(Path path, String file) {
        String content = text(path, file, "/");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(32); options.setCodePointLimit(MAX_BYTES);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            for (Event event : yaml.parse(new StringReader(content))) {
                if (event instanceof AliasEvent || event instanceof NodeEvent n && n.getAnchor() != null)
                    throw error(file, "/line:"+(event.getStartMark().getLine()+1)+"/column:"+(event.getStartMark().getColumn()+1), "不允许锚点和别名");
            }
            Node node = yaml.compose(new StringReader(content));
            if (node == null) throw error(file, "/", "文档不能为空");
            return convert(node, file);
        } catch (ConfigurationException e) { throw e; }
        catch (org.yaml.snakeyaml.error.MarkedYAMLException e) {
            String location = e.getProblemMark() == null ? "/" : "/line:" + (e.getProblemMark().getLine()+1) + "/column:" + (e.getProblemMark().getColumn()+1);
            throw error(file, location, "YAML结构、标签或资源限制无效");
        } catch (Exception e) { throw error(file, "/", "YAML结构、标签或资源限制无效"); }
    }

    /** 显式节点转换保留类型，拒绝隐式日期、二进制、复杂键和宽松数值。 */
    private JsonNode convert(Node rootNode, String file) {
        record Visit(Node node, String field, int depth, java.util.function.Consumer<JsonNode> assign) { }
        JsonNode[] result = new JsonNode[1];
        Deque<Visit> pending = new ArrayDeque<>();
        pending.add(new Visit(rootNode,"/",0,value -> result[0]=value));
        while(!pending.isEmpty()) {
            Visit visit=pending.removeFirst(); Node node=visit.node(); String field=visit.field();
            if(visit.depth()>32) throw error(file,field,"深度超过32");
            if(node instanceof MappingNode mapping && Tag.MAP.equals(node.getTag())) {
                ObjectNode object=json.createObjectNode(); visit.assign().accept(object); Set<String> keys=new HashSet<>();
                for(NodeTuple tuple:mapping.getValue()) {
                    if(!(tuple.getKeyNode() instanceof ScalarNode key) || !Tag.STR.equals(key.getTag()) || key.getValue().equals("<<")) throw error(file,field,"仅允许字符串字段");
                    if(!keys.add(key.getValue())) throw error(file,field+"line:"+(key.getStartMark().getLine()+1)+"/column:"+(key.getStartMark().getColumn()+1),"重复字段");
                    pending.add(new Visit(tuple.getValueNode(),field,visit.depth()+1,value -> object.set(key.getValue(),value)));
                }
            } else if(node instanceof SequenceNode sequence && Tag.SEQ.equals(node.getTag())) {
                ArrayNode array=json.createArrayNode(); visit.assign().accept(array);
                for(Node child:sequence.getValue()) {
                    int index=array.size(); array.addNull();
                    pending.add(new Visit(child,field,visit.depth()+1,value -> array.set(index,value)));
                }
            } else visit.assign().accept(scalar(node,file,field));
        }
        return result[0];
    }

    /** YAML标量只接受JSON语义，不做yes/no、日期或特殊浮点的隐式转换。 */
    private JsonNode scalar(Node node, String file, String field) {
        if (node instanceof ScalarNode scalar) {
            String value = scalar.getValue(); Tag tag = scalar.getTag();
            if (Tag.STR.equals(tag)) return json.getNodeFactory().stringNode(value);
            if (Tag.NULL.equals(tag)) return json.getNodeFactory().nullNode();
            if (Tag.BOOL.equals(tag) && Set.of("true", "false").contains(value)) return json.readTree(value);
            if ((Tag.INT.equals(tag) || Tag.FLOAT.equals(tag)) && value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) return json.readTree(value);
        }
        throw error(file, field+"line:"+(node.getStartMark().getLine()+1)+"/column:"+(node.getStartMark().getColumn()+1), "仅允许JSON类型与安全YAML标签");
    }

    /** 构造安全错误，不关联含原文/值的底层异常。 */
    static ConfigurationException error(String file, String field, String reason) { return new ConfigurationException(file, field, reason); }
}
