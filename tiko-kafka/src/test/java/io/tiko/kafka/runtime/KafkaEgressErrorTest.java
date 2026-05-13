package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaEgressError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.kafka.runtime.fixtures.ThrowingPublisher;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class KafkaEgressErrorTest {

    @Test
    void sink_throw_routes_to_egress_error_and_local_handlers_still_ran() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        TikoOptions opts = TikoOptions.builder().errorHandler(errors::add).build();

        List<GeneratedSinkDescriptor> sinks = List.of(new GeneratedSinkDescriptor(
                "fail-out",
                "",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                (container, event) -> container.get(ThrowingPublisher.class).toKafka((OrderPlaced) event)));

        try (Container container = Tiko.create(opts);
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, List.of(), sinks)) {

            container.getEventBus().publish(new OrderPlaced("o-1", 1));

            OrderRecorder recorder = container.get(OrderRecorder.class);
            assertThat(recorder.received).containsExactly(new OrderPlaced("o-1", 1));
            assertThat(broker.produced("fail-out")).isEmpty();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).isInstanceOfSatisfying(KafkaEgressError.class, e -> {
                assertThat(e.topic()).isEqualTo("fail-out");
                assertThat(e.cause()).hasMessageContaining("simulated egress failure");
            });
        }
    }
}
