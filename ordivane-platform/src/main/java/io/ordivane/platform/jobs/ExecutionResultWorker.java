package io.ordivane.platform.jobs;

import io.ordivane.platform.recovery.execution.ExecutionResults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 默认关闭的只读结果调度，每轮最多20次；重启继续数据库到期任务。 */
@Component
public class ExecutionResultWorker {
    private final ExecutionResults results;
    private final boolean enabled;
    private final int batch;
    public ExecutionResultWorker(ExecutionResults results,@Value("${ordivane.execution.result-worker-enabled:false}") boolean enabled,
            @Value("${ordivane.execution.batch-size:10}") int batch,@Value("${ordivane.execution.result-delay-ms:1000}") int delay) {
        if(batch<1 || batch>20 || delay<100 || delay>60000) throw new IllegalArgumentException("查证调度界限无效");
        this.results=results;this.enabled=enabled;this.batch=batch;
    }
    /** 不调用发送Worker；异常在任务边界记录，租约及预算限制下一轮。 */
    @Scheduled(fixedDelayString="${ordivane.execution.result-delay-ms:1000}",initialDelayString="${ordivane.execution.result-delay-ms:1000}")
    public void runBatch() {
        if(!enabled) return;
        try { results.expireAbandoned();for(int i=0;i<batch;i++) { var lease=results.claim();if(lease.isEmpty()) return;results.verify(lease.get()); } }
        catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(ExecutionResultWorker.class).error("结果核验批次失败",failure); }
    }
}
