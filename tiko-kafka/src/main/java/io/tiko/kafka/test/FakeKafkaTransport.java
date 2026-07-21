package io.tiko.kafka.test;

import io.tiko.Container;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.runtime.GeneratedSinkDescriptor;
import io.tiko.kafka.runtime.GeneratedSourceDescriptor;
import io.tiko.kafka.runtime.KafkaBootstrapSupport;
import java.util.List;
import java.util.Objects;

/**
 * Test transport that routes a {@link KafkaTransport}'s generated {@code @KafkaSource} /
 * {@code @KafkaSink} descriptors through a {@link FakeKafkaBroker} instead of real Kafka
 * clients. Intended for use with
 * {@code TikoOptions.builder().replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))}
 * — the container then owns this transport's lifecycle like any other.
 *
 * <pre>{@code
 * FakeKafkaBroker broker = new FakeKafkaBroker();
 * try (Container c = Tiko.create(TikoOptions.builder()
 *         .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
 *         .build())) {
 *     broker.produce("orders", orderJson);
 *     // assertions against broker.produced(...) and container state
 * }
 * }</pre>
 */
public final class FakeKafkaTransport implements KafkaTransport {

    private final KafkaTransport original;
    private final FakeKafkaBroker broker;
    private KafkaBootstrapSupport support;

    private FakeKafkaTransport(KafkaTransport original, FakeKafkaBroker broker) {
        this.original = original;
        this.broker = broker;
    }

    /** Wraps the generated transport's descriptors around {@code broker}'s in-memory clients. */
    public static FakeKafkaTransport over(KafkaTransport original, FakeKafkaBroker broker) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(broker, "broker");
        return new FakeKafkaTransport(original, broker);
    }

    /** Delegates to the wrapped transport's generated {@code @KafkaSource} descriptors (#432). */
    @Override
    public List<GeneratedSourceDescriptor> sources() {
        return original.sources();
    }

    /** Delegates to the wrapped transport's generated {@code @KafkaSink} descriptors (#432). */
    @Override
    public List<GeneratedSinkDescriptor> sinks() {
        return original.sinks();
    }

    @Override
    public void start(Container container) {
        if (support != null) {
            return;
        }
        support = new KafkaBootstrapSupport(
                container,
                original.sources(),
                original.sinks(),
                (config, group) -> broker.consumerClient(group),
                config -> broker.producerClient());
        support.start();
    }

    @Override
    public void shutdown() {
        if (support != null) {
            support.shutdown();
            support = null;
        }
    }
}
