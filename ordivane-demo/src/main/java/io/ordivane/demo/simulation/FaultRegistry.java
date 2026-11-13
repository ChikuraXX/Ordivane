package io.ordivane.demo.simulation;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 仅local显式开启的实例内故障；按逻辑事件精确隔离，重启自动复位。 */
@Component
@Profile("local")
@ConditionalOnProperty(name="demo.simulation.enabled", havingValue="true")
public class FaultRegistry {
    /** 三种互斥故障，非法Schema由生产端生成，消费者仍严格拒绝。 */
    public enum Scenario { DOWNSTREAM_UNAVAILABLE, COMMITTED_RESPONSE_TIMEOUT, INVALID_SCHEMA }
    private final ConcurrentHashMap<UUID, Scenario> targets = new ConcurrentHashMap<>();
    /** 单实例最多100个故障目标，避免无限增长；同一目标可替换或复位。 */
    public synchronized void enable(UUID eventId, Scenario scenario) {
        if (scenario == null || (!targets.containsKey(eventId) && targets.size() >= 100)) {
            throw new io.ordivane.demo.order.BusinessException("INVALID_FAULT", 400, "故障参数无效或目标已达上限");
        }
        targets.put(eventId, scenario);
    }
    /** 复位只影响指定事件，不删除业务、报告或消息。 */
    public void reset(UUID eventId) { targets.remove(eventId); }
    /** 查当前事件故障；未配置返回null，调用方保持正常业务路径。 */
    public Scenario scenario(UUID eventId) { return targets.get(eventId); }
}
