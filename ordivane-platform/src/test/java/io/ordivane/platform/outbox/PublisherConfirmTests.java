package io.ordivane.platform.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import static org.assertj.core.api.Assertions.*;

/** 区分broker协议NACK和Spring在连接/通道关闭时合成的否认，不能混记。 */
class PublisherConfirmTests {
    @Test void returnedAckNackAndConnectionFailureRemainDistinct() {
        assertThat(RecoveryPublisher.confirmedOutcome(new CorrelationData.Confirm(true,null),false)).isEqualTo("ACK");
        assertThat(RecoveryPublisher.confirmedOutcome(new CorrelationData.Confirm(true,null),true)).isEqualTo("RETURNED");
        assertThat(RecoveryPublisher.confirmedOutcome(new CorrelationData.Confirm(false,null),false)).isEqualTo("NACK");
        assertThat(RecoveryPublisher.confirmedOutcome(new CorrelationData.Confirm(false,"channel closed"),false)).isEqualTo("CONNECTION_LOST");
    }
}
