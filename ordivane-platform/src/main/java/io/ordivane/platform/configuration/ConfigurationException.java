package io.ordivane.platform.configuration;

/** 启动配置错误，只携带可信字段位置与固定原因，不保留解析器原文或秘密。 */
public final class ConfigurationException extends RuntimeException {
    /** file/field 由加载器生成，reason 为固定规则说明。 */
    public ConfigurationException(String file, String field, String reason) {
        super("配置拒绝 " + file + "#" + field + ": " + reason);
    }
}
