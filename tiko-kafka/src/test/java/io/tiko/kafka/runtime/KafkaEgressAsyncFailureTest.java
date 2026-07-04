package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaEgressError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.client.KafkaProducerClient;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;

/**
 * An asynchronous producer failure — broker-side rejection reported through the send
 * callback (record-too-large, topic authorization, delivery timeout) rather than a
 * synchronous throw — must reach the ErrorHandler as a {@code KafkaEgressError} with the
 * event and topic identified (#342). Previously the send future was discarded and such
 * failures vanished silently.
 */
class KafkaEgressAsyncFailureTest {

    /** Producer whose broker always rejects asynchronously, via the callback. */
    private static final class AsyncFailingProducerClient implements KafkaProducerClient {
        @Override
        public Future<RecordMetadata> send(ProducerRecord<String, byte[]> producerRecord, Callback callback) {
            RuntimeException rejection = new RuntimeException("broker rejected: record too large");
            if (callback != null) {
                callback.onCompletion(null, rejection);
            }
            return CompletableFuture.failedFuture(rejection);
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    @Test
    void asyncBrokerRejectionRoutesToTheErrorHandler() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        TikoOptions opts = TikoOptions.builder().errorHandler(errors::add).build();

        List<GeneratedSinkDescriptor> sinks = List.of(new GeneratedSinkDescriptor(
                "async-fail-out",
                "",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                (container, event) -> event,
                p -> null));

        try (Container container = Tiko.create(opts)) {
            KafkaBootstrapSupport support = new KafkaBootstrapSupport(
                    container,
                    List.of(),
                    sinks,
                    (config, group) -> broker.consumerClient(group),
                    config -> new AsyncFailingProducerClient());
            support.start();
            try {
                container.getEventBus().publish(new OrderPlaced("o-2", 5));

                await().atMost(Duration.ofSeconds(5)).until(() -> !errors.isEmpty());
            } finally {
                support.shutdown();
            }
        }

        assertThat(errors.get(0)).isInstanceOfSatisfying(KafkaEgressError.class, e -> {
            assertThat(e.topic()).isEqualTo("async-fail-out");
            assertThat(e.event()).isEqualTo(new OrderPlaced("o-2", 5));
            assertThat(e.cause()).hasMessageContaining("record too large");
        });
    }

    @Test
    void throwingErrorHandlerDoesNotPropagateFromTheSendCallback() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        TikoOptions opts = TikoOptions.builder()
                .errorHandler(ctx -> {
                    throw new IllegalStateException("error handler failure");
                })
                .build();

        List<GeneratedSinkDescriptor> sinks = List.of(new GeneratedSinkDescriptor(
                "handler-throws",
                "",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                (container, event) -> event,
                p -> null));

        try (Container container = Tiko.create(opts)) {
            KafkaBootstrapSupport support = new KafkaBootstrapSupport(
                    container,
                    List.of(),
                    sinks,
                    (config, group) -> broker.consumerClient(group),
                    config -> new AsyncFailingProducerClient());
            support.start();
            try {
                // The send callback runs on the producer thread (synchronously here); a
                // throwing ErrorHandler must be contained, not escape through publish.
                org.assertj.core.api.Assertions.assertThatCode(
                                () -> container.getEventBus().publish(new OrderPlaced("o-3", 1)))
                        .doesNotThrowAnyException();
            } finally {
                support.shutdown();
            }
        }
    }

    @Test
    void fireAndForgetSendDelegatesThroughTheCallbackVariant() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        KafkaProducerClient producer = broker.producerClient();

        producer.send(new ProducerRecord<>("plain-send", "x".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(broker.produced("plain-send")).hasSize(1);
    }
}
