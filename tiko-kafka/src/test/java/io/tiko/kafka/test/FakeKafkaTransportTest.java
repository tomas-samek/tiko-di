package io.tiko.kafka.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.runtime.GeneratedSinkDescriptor;
import io.tiko.kafka.runtime.GeneratedSourceDescriptor;
import io.tiko.kafka.runtime.fixtures.OrderKafkaPublisher;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeKafkaTransportTest {

    /** Hand-built stand-in for the generated bootstrap: real descriptors, no real clients. */
    private static KafkaTransport topology() {
        return new KafkaTransport() {
            @Override
            public List<GeneratedSourceDescriptor> sources() {
                return List.of();
            }

            @Override
            public List<GeneratedSinkDescriptor> sinks() {
                return List.of(new GeneratedSinkDescriptor(
                        "orders-out",
                        "orderId",
                        OrderPlaced.class,
                        KafkaSerializer.Default.class,
                        (container, event) ->
                                container.get(OrderKafkaPublisher.class).toKafka((OrderPlaced) event),
                        p -> String.valueOf(((OrderPlaced) p).orderId())));
            }

            @Override
            public void start(Container container) {
                throw new AssertionError("the replaced transport must never be started");
            }

            @Override
            public void shutdown() {
                /* no-op test fixture */
            }
        };
    }

    @Test
    void routesGeneratedSinkDescriptorsThroughTheFakeBroker() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        try (Container container = Tiko.create()) {
            FakeKafkaTransport fake = FakeKafkaTransport.over(topology(), broker);
            fake.start(container);
            try {
                container.getEventBus().publish(new OrderPlaced("o-9", 4));

                assertThat(broker.produced("orders-out")).hasSize(1);
                assertThat(broker.produced("orders-out").get(0).key()).isEqualTo("o-9");
            } finally {
                fake.shutdown();
            }
        }
    }

    @Test
    void startIsIdempotentAndShutdownDelegates() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        try (Container container = Tiko.create()) {
            FakeKafkaTransport fake = FakeKafkaTransport.over(topology(), broker);
            fake.start(container);
            fake.start(container); // second start must be a no-op (TransportBootstrap contract)

            container.getEventBus().publish(new OrderPlaced("o-1", 1));
            assertThat(broker.produced("orders-out")).hasSize(1);

            fake.shutdown();
            fake.shutdown(); // second shutdown must be a no-op
        }
    }

    @Test
    void isAKafkaTransportAndDelegatesDescriptorsToTheWrapped() {
        // #432: the fake must itself be a KafkaTransport (type symmetry — so it can be the target of
        // a further replaceTransport(KafkaTransport.class, ...) match and satisfy code written
        // against the interface), delegating the descriptor accessors to the wrapped transport.
        List<GeneratedSourceDescriptor> sources = List.of();
        List<GeneratedSinkDescriptor> sinks = List.of(new GeneratedSinkDescriptor(
                "orders-out",
                "orderId",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                (container, event) -> container.get(OrderKafkaPublisher.class).toKafka((OrderPlaced) event),
                p -> String.valueOf(((OrderPlaced) p).orderId())));
        KafkaTransport original = new KafkaTransport() {
            @Override
            public List<GeneratedSourceDescriptor> sources() {
                return sources;
            }

            @Override
            public List<GeneratedSinkDescriptor> sinks() {
                return sinks;
            }

            @Override
            public void start(Container container) {
                throw new AssertionError("the wrapped transport must never be started");
            }

            @Override
            public void shutdown() {
                /* no-op test fixture */
            }
        };

        FakeKafkaTransport fake = FakeKafkaTransport.over(original, new FakeKafkaBroker());

        assertThat(fake).isInstanceOf(KafkaTransport.class);
        assertThat(fake.sources()).isSameAs(sources);
        assertThat(fake.sinks()).isSameAs(sinks);
    }

    @Test
    void substitutionFailsFastWhenNoKafkaTransportIsDiscovered() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        // No generated transport is on tiko-kafka's own test classpath, so the discovered
        // list is empty — assert the fail-fast contract holds for the kafka key too.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Tiko.create(TikoOptions.builder()
                        .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
                        .build()))
                .hasMessageContaining("KafkaTransport");
    }
}
