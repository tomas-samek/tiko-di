package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.ErrorContext;
import io.tiko.UndecodablePayloadException;
import io.tiko.config.ConfigSources;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.client.KafkaConsumerClient;
import io.tiko.kafka.client.KafkaProducerClient;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;

/**
 * Edges of the Kafka runtime found in the 0.7.0 review: an {@code Error} in a bridge (#749), a
 * record without a value (#751), a start that fails half-way (#752), a sink bridge that returns
 * {@code null} (#754), and the fake broker's poll timeout (#755).
 */
class KafkaRuntimeEdgeCasesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LONG_POLL = Duration.ofSeconds(30);

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** #749: an Error stops the consumer; that is reported with the record, which stays uncommitted. */
    @Test
    void errorFromABridgeIsReportedAndItsRecordIsNotCommitted() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        broker.produce("errors", utf8("first"));
        broker.produce("errors", utf8("fatal"));
        broker.produce("errors", utf8("third"));
        List<Long> committed = new CopyOnWriteArrayList<>();
        KafkaConsumerClient client = new ForwardingClient(broker.consumerClient("g")) {
            @Override
            public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
                offsets.values().forEach(offset -> committed.add(offset.offset()));
                super.commitSync(offsets);
            }
        };
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        List<Object> received = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            container.getEventBus().subscribe(String.class, received::add);
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("errors", payload -> {
                        if (payload.equals("fatal")) {
                            throw new NoClassDefFoundError("com/example/Missing");
                        }
                        return payload;
                    }),
                    client,
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config());
            runner.start();
            try {
                await().atMost(TIMEOUT).until(() -> !errors.isEmpty());
            } finally {
                runner.stop();
            }
        }

        assertThat(errors).singleElement().isInstanceOfSatisfying(KafkaIngestError.class, error -> {
            assertThat(error.cause()).isInstanceOf(NoClassDefFoundError.class);
            assertThat(error.offset()).isEqualTo(1L);
        });
        assertThat(received).as("the consumer stopped at the failing record").containsExactly("first");
        assertThat(committed).as("only the first record was acknowledged").containsExactly(1L);
    }

    /** #749: an Error outside a record (the client) is reported without coordinates and stops the consumer. */
    @Test
    void errorFromThePollIsReportedAndStopsTheConsumer() {
        AtomicInteger polls = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        KafkaConsumerClient client = new ForwardingClient(new FakeKafkaBroker().consumerClient("g")) {
            @Override
            public ConsumerRecords<String, byte[]> poll(Duration timeout) {
                polls.incrementAndGet();
                throw new StackOverflowError("in the client");
            }

            @Override
            public void close() {
                closed.set(true);
                super.close();
            }
        };
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("errors", payload -> payload),
                    client,
                    container,
                    container.getEventBus(),
                    // A handler that fails on the report must not keep the consumer from closing.
                    error -> {
                        errors.add(error);
                        throw new IllegalStateException("handler failed");
                    },
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config());
            runner.start();
            try {
                await().atMost(TIMEOUT).untilTrue(closed);
            } finally {
                runner.stop();
            }
        }

        assertThat(polls).as("the consumer did not poll again").hasValue(1);
        assertThat(errors).singleElement().isInstanceOfSatisfying(KafkaIngestError.class, error -> {
            assertThat(error.cause()).isInstanceOf(StackOverflowError.class);
            assertThat(error.offset()).isEqualTo(-1L);
        });
    }

    /** #751: a record without a value is consumed and publishes nothing; the partition moves on. */
    @Test
    void recordWithoutAValueIsConsumedAndPublishesNothing() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        broker.produce("tombstones", null);
        broker.produce("tombstones", utf8("after"));
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        List<Object> received = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            container.getEventBus().subscribe(String.class, received::add);
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("tombstones", payload -> payload),
                    broker.consumerClient("g"),
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config());
            runner.start();
            try {
                await().atMost(TIMEOUT).until(() -> received.contains("after"));
            } finally {
                runner.stop();
            }
        }

        assertThat(received).containsExactly("after");
        assertThat(errors).isEmpty();
    }

    @Test
    void bundledSerializerReportsANullValueAsUndecodable() {
        var serializer = new JsonKafkaSerializer();

        assertThatThrownBy(() -> serializer.deserialize(null, OrderPlaced.class))
                .isInstanceOf(UndecodablePayloadException.class)
                .hasMessageContaining("no value");
    }

    /** A serializer the runtime cannot instantiate: no public no-arg constructor. */
    static final class UnbuildableSerializer implements KafkaSerializer {
        UnbuildableSerializer(String required) {
            assertThat(required).isNotNull();
        }

        @Override
        public byte[] serialize(Object value) {
            return new byte[0];
        }

        @Override
        public <T> T deserialize(byte[] bytes, Class<T> type) {
            return null;
        }
    }

    /** The sink of the two #752 tests: a start that creates a producer for it. */
    private static final GeneratedSinkDescriptor OUT_SINK = new GeneratedSinkDescriptor(
            "out", "", OrderPlaced.class, KafkaSerializer.Default.class, (container, event) -> event, p -> null);

    /** A consumer factory over {@code broker} that counts in {@code openConsumers} the clients not closed yet. */
    private static BiFunction<KafkaConfig, String, KafkaConsumerClient> countingConsumers(
            FakeKafkaBroker broker, AtomicInteger openConsumers) {
        return (config, group) -> {
            openConsumers.incrementAndGet();
            return new ForwardingClient(broker.consumerClient(group)) {
                @Override
                public void close() {
                    openConsumers.decrementAndGet();
                    super.close();
                }
            };
        };
    }

    /** #752: a start that fails after it created clients closes them before it throws. */
    @Test
    void startThatFailsHalfWayStopsTheConsumersAndClosesTheProducerItCreated() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        AtomicInteger openConsumers = new AtomicInteger();
        AtomicBoolean producerClosed = new AtomicBoolean();
        var good = RunnerTestSupport.stringSource("good", payload -> payload);
        var bad = new GeneratedSourceDescriptor(
                "bad",
                "",
                "StringEvent",
                String.class,
                UnbuildableSerializer.class,
                false,
                (container, payload, ctx) -> payload);

        try (Container container = Tiko.create()) {
            var support = new KafkaBootstrapSupport(
                    container,
                    List.of(good, bad),
                    List.of(OUT_SINK),
                    countingConsumers(broker, openConsumers),
                    config -> new ClosingProducer(broker.producerClient(), producerClosed));

            assertThatThrownBy(support::start)
                    .isInstanceOf(ContainerInitializationException.class)
                    .hasMessageContaining("UnbuildableSerializer");

            await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(openConsumers)
                            .as("every consumer the failed start created is closed")
                            .hasValue(0));
            assertThat(producerClosed).isTrue();
        }
    }

    /** #752: a runner that cannot be built (a policy typo) closes the client created for it. */
    @Test
    void startWithAnUnknownPolicyClosesTheClientsItCreated() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        AtomicInteger openConsumers = new AtomicInteger();
        AtomicBoolean producerClosed = new AtomicBoolean();
        var kafka = Map.<String, Object>of("poison-record-policy", "SKIPP");
        TikoOptions options = TikoOptions.builder()
                .configSource(ConfigSources.fromMap(Map.of("tiko", Map.of("kafka", kafka))))
                .build();

        try (Container container = Tiko.create(options)) {
            var support = new KafkaBootstrapSupport(
                    container,
                    List.of(RunnerTestSupport.stringSource("good", payload -> payload)),
                    List.of(OUT_SINK),
                    countingConsumers(broker, openConsumers),
                    config -> new ClosingProducer(broker.producerClient(), producerClosed));

            assertThatThrownBy(support::start).hasMessageContaining("SKIPP");

            assertThat(openConsumers).hasValue(0);
            assertThat(producerClosed).isTrue();
        }
    }

    /** #754: a sink bridge that returns null sends nothing, with or without a partition key. */
    @Test
    void sinkBridgeReturningNullSendsNothing() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        TikoOptions options = TikoOptions.builder().errorHandler(errors::add).build();
        List<GeneratedSinkDescriptor> sinks = List.of(
                new GeneratedSinkDescriptor(
                        "plain",
                        "",
                        OrderPlaced.class,
                        KafkaSerializer.Default.class,
                        (container, event) -> null,
                        payload -> null),
                new GeneratedSinkDescriptor(
                        "keyed",
                        "orderId",
                        OrderPlaced.class,
                        KafkaSerializer.Default.class,
                        (container, event) -> null,
                        payload -> ((OrderPlaced) payload).orderId()));

        try (Container container = Tiko.create(options);
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, List.of(), sinks)) {
            container.getEventBus().publish(new OrderPlaced("o-1", 1));

            assertThat(broker.produced("plain")).isEmpty();
            assertThat(broker.produced("keyed")).isEmpty();
            assertThat(errors).isEmpty();
        }
    }

    /** #755: an idle fake consumer waits out the poll timeout instead of returning at once. */
    @Test
    void fakeConsumerWaitsForThePollTimeoutOnAnIdleTopic() {
        var consumer = new FakeKafkaBroker().consumerClient("g");
        consumer.subscribe(List.of("idle"));

        long started = System.nanoTime();
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(300));

        assertThat(records.isEmpty()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(250));
    }

    @Test
    void fakeConsumerWakesAsSoonAsARecordArrives() {
        var broker = new FakeKafkaBroker();
        var consumer = broker.consumerClient("g");
        consumer.subscribe(List.of("live"));
        Thread.ofPlatform().daemon().start(() -> {
            await().pollDelay(Duration.ofMillis(100)).atMost(TIMEOUT).until(() -> true);
            broker.produce("live", utf8("r0"));
        });

        long started = System.nanoTime();
        ConsumerRecords<String, byte[]> records = consumer.poll(LONG_POLL);

        // The record itself shows the producing thread ran. Whether that thread has already ended
        // is not asserted: the consumer is woken from inside produce(), before the thread returns.
        assertThat(records.count()).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void fakeConsumerWaitingInPollIsReleasedByWakeup() {
        var consumer = new FakeKafkaBroker().consumerClient("g");
        consumer.subscribe(List.of("idle"));
        Thread.ofPlatform().daemon().start(() -> {
            await().pollDelay(Duration.ofMillis(100)).atMost(TIMEOUT).until(() -> true);
            consumer.wakeup();
        });

        long started = System.nanoTime();
        assertThatThrownBy(() -> consumer.poll(LONG_POLL)).isInstanceOf(WakeupException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void fakeConsumerInterruptedInPollThrowsLikeTheRealOne() {
        var consumer = new FakeKafkaBroker().consumerClient("g");
        consumer.subscribe(List.of("idle"));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> consumer.poll(LONG_POLL)).isInstanceOf(InterruptException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clears the flag this test set, so it does not reach the next test.
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    private static final class ClosingProducer implements KafkaProducerClient {
        private final KafkaProducerClient delegate;
        private final AtomicBoolean closed;

        ClosingProducer(KafkaProducerClient delegate, AtomicBoolean closed) {
            this.delegate = delegate;
            this.closed = closed;
        }

        @Override
        public Future<RecordMetadata> send(ProducerRecord<String, byte[]> producerRecord, Callback callback) {
            return delegate.send(producerRecord, callback);
        }

        @Override
        public void close() {
            closed.set(true);
            delegate.close();
        }
    }
}
