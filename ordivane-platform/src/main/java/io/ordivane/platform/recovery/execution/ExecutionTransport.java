package io.ordivane.platform.recovery.execution;

import io.ordivane.platform.configuration.ConfigurationLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** 固定逻辑目标的服务端快照；仅local显式开关允许任务专用隔离前缀。 */
@Component
public class ExecutionTransport {
    private final Target target;
    /** 启动即拒绝非法覆盖，不等到第一次发送。 */
    public ExecutionTransport(Environment environment,
            @Value("${ordivane.execution.local-mapping-enabled:false}") boolean enabled,
            @Value("${ordivane.execution.local-prefix:}") String prefix) {
        target=resolve(enabled,prefix,environment.acceptsProfiles(Profiles.of("local")));
    }
    /** 传输指纹同时绑定逻辑目标、exchange、routing key与queue。 */
    public record Target(String exchange,String routingKey,String queue,String fingerprint) { }
    /** 测试映射不接受URL、任意目标或其他任务前缀。 */
    public static Target resolve(boolean enabled,String prefix,boolean local) {
        if(enabled && (!local || !prefix.matches("ordivane\\.test\\.be060\\.[a-z0-9]{1,40}"))
                || !enabled && !prefix.isEmpty()) throw new IllegalArgumentException("执行传输覆盖仅允许local显式BE060测试前缀");
        String base=enabled?prefix:"ordivane.demo";
        String document="{\"exchange\":\""+base+".recovery\",\"id\":\"membership-replay\",\"queue\":\""+base+".membership.recovery\",\"routingKey\":\"membership.activate\"}";
        return new Target(base+".recovery","membership.activate",base+".membership.recovery",ConfigurationLoader.sha256(document));
    }
    /** 当前受信启动环境下的不可变传输。 */
    public Target target() { return target; }
}
