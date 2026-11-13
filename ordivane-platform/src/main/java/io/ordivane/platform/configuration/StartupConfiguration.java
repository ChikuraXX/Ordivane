package io.ordivane.platform.configuration;

import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import static io.ordivane.platform.configuration.SafeConfigurationFiles.error;

/** 组件扫描接入启动加载；只有不可变版本Bean，没有编辑或热更新端点。 */
@Configuration(proxyBeanMethods=false)
public class StartupConfiguration {
    /** 环境变量由当前进程提供；业务Spring连接参数仍由已接受入口管理。 */
    @Bean
    @DependsOnDatabaseInitialization
    ConfigurationLoader.Loaded loadedConfiguration(Environment environment, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        Map<String,String> variables=new HashMap<>(System.getenv());
        Path root=Path.of(environment.getRequiredProperty("ordivane.configuration.root"));
        ConfigurationLoader.Loaded loaded=new ConfigurationLoader().load(root,variables);
        if(environment.getProperty("ordivane.ingestion.enabled",Boolean.class,false)) {
            if(!loaded.sourceId().equals(environment.getProperty("ordivane.ingestion.source"))) throw error("ordivane.yml","/connections/rabbitmq/source_id","与可信接入映射不一致");
            if(!"ordivane.demo.membership.failed".equals(environment.getProperty("ordivane.ingestion.queue"))) throw error("ordivane.yml","/connections/rabbitmq/dead_letter_queue","与实际接入队列不一致");
            if(!"membership.activate".equals(environment.getProperty("ordivane.ingestion.handler"))) throw error("ordivane.yml","/scenario/match/handler","与实际接入处理器不一致");
            Integer count=jdbc.queryForObject("SELECT count(*) FROM access_source WHERE source_id=? AND enabled=true",Integer.class,loaded.sourceId());
            if(count==null || count!=1) throw error("ordivane.yml","/connections/rabbitmq/source_id","来源身份未启用注册");
        }
        new ConfigurationVersionStore(jdbc,new TransactionTemplate(transactions)).register(loaded);
        return loaded;
    }
}
