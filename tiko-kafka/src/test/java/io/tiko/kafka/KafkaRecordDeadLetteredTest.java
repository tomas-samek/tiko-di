package io.tiko.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ErrorContext;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class KafkaRecordDeadLetteredTest {

    @Test
    void carriesRecordCoordinatesAndIsAKafkaTransportError() {
        Throwable cause = new IllegalStateException("bad shape");
        KafkaRecordDeadLettered dl = new KafkaRecordDeadLettered("orders", 2, 42L, new RecordHeaders(), cause, 3);

        assertThat(dl.topic()).isEqualTo("orders");
        assertThat(dl.partition()).isEqualTo(2);
        assertThat(dl.offset()).isEqualTo(42L);
        assertThat(dl.attempts()).isEqualTo(3);
        assertThat(dl.cause()).isSameAs(cause);
        assertThat(dl.transport()).isEqualTo("kafka");
        assertThat(dl).isInstanceOf(ErrorContext.class);
    }
}
