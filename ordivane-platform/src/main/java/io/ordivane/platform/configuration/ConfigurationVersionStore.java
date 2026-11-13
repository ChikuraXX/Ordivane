package io.ordivane.platform.configuration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static io.ordivane.platform.configuration.SafeConfigurationFiles.error;

/** 追加不可变配置版本；同摘要重启复用，碰撞/损坏绝不覆盖历史。 */
public final class ConfigurationVersionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /** 复用平台JDBC与事务管理器，版本追加和复用核对处于同一事务。 */
    public ConfigurationVersionStore(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc=jdbc; this.transaction=transaction;
    }

    /** 返回已登记摘要；配置加载不创建或失效任何尚未实现的业务计划。 */
    public String register(ConfigurationLoader.Loaded loaded) {
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO configuration_version(digest, snapshot) VALUES (?, ?) ON CONFLICT (digest) DO NOTHING",loaded.digest(),loaded.snapshot());
            String stored=jdbc.queryForObject("SELECT snapshot FROM configuration_version WHERE digest=?",String.class,loaded.digest());
            if(!loaded.snapshot().equals(stored)) throw error("ordivane.yml","/snapshot","同摘要历史内容不一致");
            return loaded.digest();
        });
    }
}
