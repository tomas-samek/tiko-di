package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.kafka.IngestErrorPolicy;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SEC-2 for {@code tiko.kafka.*} (#810): a startup failure over a setting names the key and what
 * is accepted, never the configured value, which a wrong interpolation can make a secret.
 */
class KafkaStartupMessagesTest {

    private static final String SECRET = "Sup3rSecretPw";

    private static String printed(Throwable failure) {
        var logged = new StringWriter();
        failure.printStackTrace(new PrintWriter(logged));
        return logged.toString();
    }

    @Test
    void unknownPoisonRecordPolicyNamesTheKeyAndTheValidValues() {
        var failure = catchThrowable(() -> IngestErrorPolicy.parse(SECRET));

        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        assertThat(printed(failure))
                .contains("tiko.kafka.poison-record-policy", "SEEK", "SKIP")
                .doesNotContain(SECRET);
    }

    @Test
    void unknownSerializerNamesTheKeyAndTheKnownNames() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        var kafka = Map.<String, Object>of("serializer", SECRET);
        TikoOptions options = TikoOptions.builder()
                .configSource(ConfigSources.fromMap(Map.of("tiko", Map.of("kafka", kafka))))
                .build();

        try (Container container = Tiko.create(options)) {
            var support = new KafkaBootstrapSupport(
                    container,
                    List.of(RunnerTestSupport.stringSource("orders", payload -> payload)),
                    List.of(),
                    (config, group) -> broker.consumerClient(group),
                    config -> broker.producerClient());

            var failure = catchThrowable(support::start);

            assertThat(failure).isNotNull();
            assertThat(printed(failure))
                    .contains("tiko.kafka.serializer", "json")
                    .doesNotContain(SECRET);
        }
    }
}
