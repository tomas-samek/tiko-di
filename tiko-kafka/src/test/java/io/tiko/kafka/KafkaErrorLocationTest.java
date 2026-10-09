package io.tiko.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.TransportError;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** #516: Kafka errors name where they happened, so the default log line points at the record. */
class KafkaErrorLocationTest {

    private static final RuntimeException CAUSE = new RuntimeException("boom");

    static Stream<Arguments> locations() {
        var secretHeaders = new RecordHeaders().add("auth", "secret-token".getBytes(StandardCharsets.UTF_8));
        return Stream.of(
                Arguments.of("ingest", new KafkaIngestError("orders", 3, 1042L, secretHeaders, CAUSE), "orders-3@1042"),
                Arguments.of(
                        "poll failure", new KafkaIngestError("orders", -1, -1L, new RecordHeaders(), CAUSE), "orders"),
                Arguments.of(
                        "dead-lettered",
                        new KafkaRecordDeadLettered("orders", 3, 1042L, secretHeaders, CAUSE, 5),
                        "orders-3@1042"),
                Arguments.of("egress", new KafkaEgressError("orders", "secret-event", CAUSE), "orders"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("locations")
    void namesTheTopicPartitionAndOffsetButNoValue(String name, TransportError error, String expected) {
        assertThat(error.location()).isEqualTo(expected).doesNotContain("secret");
    }
}
