package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.events.EventEndingEvent;
import io.tiko.kafka.runtime.fixtures.UnitScopeProbe;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * A consumed Kafka message is one unit of work (#347): the runner opens an EVENT scope
 * around dispatch + publish, so an EVENT-scoped bean resolves during handling (it threw
 * {@code NoActiveEventScopeException} before), tears down when the message completes, and a
 * unit {@code EventEndingEvent} is published. Async handlers of a consumed event detach to
 * the executor and get their own fresh scope — that is #220's concern, not this one.
 */
class KafkaUnitOfWorkScopeTest {

    @Test
    void consumedMessageOpensAnEventScopeSoEventBeansResolveAndTearDown() {
        UnitScopeProbe.reset();
        TopicPartition p0 = new TopicPartition("unit", 0);
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(
                new ConsumerRecords<>(Map.of(p0, List.of(RunnerTestSupport.consumerRecord("unit", 0, 0, "ok"))))));
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        List<Object> resolved = new CopyOnWriteArrayList<>();
        List<EventEndingEvent> ending = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            container.getEventBus().subscribe(EventEndingEvent.class, ending::add);
            // Resolving an EVENT-scoped bean here only works if the consumer opened a unit-of-work frame.
            container.getEventBus().subscribe(String.class, s -> resolved.add(container.get(UnitScopeProbe.class)));

            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("unit", payload -> payload),
                    client,
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config());
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> !resolved.isEmpty());
            } finally {
                runner.stop();
            }
        }

        assertThat(resolved)
                .as("EVENT-scoped bean must resolve inside the per-message frame")
                .hasSize(1);
        assertThat(errors)
                .as("resolution must not fail and route an ingest error")
                .isEmpty();
        assertThat(UnitScopeProbe.DESTROYED.get())
                .as("the unit's EVENT bean must be torn down when the message completes")
                .isEqualTo(1);
        assertThat(ending)
                .as("a unit EventEndingEvent must be published per message")
                .hasSize(1);
    }
}
