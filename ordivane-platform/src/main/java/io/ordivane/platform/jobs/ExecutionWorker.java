package io.ordivane.platform.jobs;

import io.ordivane.platform.ingestion.IngestionException;
import io.ordivane.platform.outbox.ExecutionOutbox;
import io.ordivane.platform.outbox.RecoveryPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** 发送默认关闭；每批有界，未知发送永不自动重放。 */
@Component
@EnableScheduling
public class ExecutionWorker {
    private final ExecutionOutbox outbox;
    private final RecoveryPublisher publisher;
    private final boolean enabled;
    private final int batchSize;
    public ExecutionWorker(ExecutionOutbox outbox,RecoveryPublisher publisher,
            @Value("${ordivane.execution.worker-enabled:false}") boolean worker,
            @Value("${ordivane.execution.outbox-enabled:false}") boolean sending,
            @Value("${ordivane.execution.batch-size:10}") int batchSize,
            @Value("${ordivane.execution.delay-ms:1000}") int delay,
            @Value("${ordivane.execution.lease-seconds:30}") int lease,
            @Value("${ordivane.execution.confirm-timeout-ms:5000}") int confirm) {
        if(batchSize<1 || batchSize>20 || delay<100 || delay>60000 || lease*1000<=confirm+1000) throw new IllegalArgumentException("执行批次/间隔/租约界限无效");
        this.outbox=outbox;this.publisher=publisher;enabled=worker&&sending;this.batchSize=batchSize;
    }
    /** 仅PENDING可接管；进程崩溃后已STARTED保持UNKNOWN供后继只读核验。 */
    @Scheduled(fixedDelayString="${ordivane.execution.delay-ms:1000}",initialDelayString="${ordivane.execution.delay-ms:1000}")
    public void runBatch() {
        if(!enabled) return;
        for(int i=0;i<batchSize;i++) {
            var claimed=outbox.claim();if(claimed.isEmpty()) return;var lease=claimed.get();
            try {
                var dispatch=outbox.begin(lease);
                if(dispatch.isPresent()) outbox.finish(dispatch.get(),publisher.publish(dispatch.get()));
            } catch(IngestionException | AccessDeniedException rejected) { outbox.block(lease); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();return; }
            catch(RuntimeException failure) {
                // STARTED已持久UNKNOWN；不吞为成功，不自动释放资格或重发。
                org.slf4j.LoggerFactory.getLogger(ExecutionWorker.class).error("执行任务失败 executionId={}",lease.executionId(),failure);return;
            }
        }
    }
}
