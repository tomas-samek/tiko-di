package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.kafka.IngestDecision;
import io.tiko.kafka.KafkaIngestErrorDecider;
import java.util.List;
import org.junit.jupiter.api.Test;

class KafkaBootstrapDeciderResolutionTest {

    @Test
    void noDecidersResolvesToNullSoTheStaticPolicyRuns() {
        assertThat(KafkaBootstrapSupport.resolveDecider(List.of())).isNull();
    }

    @Test
    void exactlyOneDeciderIsUsed() {
        KafkaIngestErrorDecider only = (error, attempt) -> IngestDecision.SKIP;
        assertThat(KafkaBootstrapSupport.resolveDecider(List.of(only))).isSameAs(only);
    }

    @Test
    void multipleDecidersFailFast() {
        KafkaIngestErrorDecider a = (error, attempt) -> IngestDecision.SKIP;
        KafkaIngestErrorDecider b = (error, attempt) -> IngestDecision.SEEK;
        assertThatThrownBy(() -> KafkaBootstrapSupport.resolveDecider(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at most one");
    }
}
