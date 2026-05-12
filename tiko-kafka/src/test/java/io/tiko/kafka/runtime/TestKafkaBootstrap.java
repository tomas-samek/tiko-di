package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.kafka.test.FakeKafkaBroker;
import java.util.List;

/**
 * Helper that wires a {@link FakeKafkaBroker} into a {@link KafkaBootstrapSupport} and
 * starts it. Returned as {@link AutoCloseable} so tests can use try-with-resources.
 *
 * <p>Tests supply explicit source and sink descriptor lists (built inline using lambdas)
 * instead of relying on generated code, which keeps this helper independent of the
 * annotation-processor output and avoids real Kafka client construction during unit tests.
 */
final class TestKafkaBootstrap implements AutoCloseable {

    private final KafkaBootstrapSupport support;

    private TestKafkaBootstrap(KafkaBootstrapSupport support) {
        this.support = support;
    }

    /**
     * Creates and starts a {@link KafkaBootstrapSupport} wired to the given broker, using
     * the provided source and sink descriptors.
     */
    static TestKafkaBootstrap start(
            Container container,
            FakeKafkaBroker broker,
            List<GeneratedSourceDescriptor> sources,
            List<GeneratedSinkDescriptor> sinks) {
        KafkaBootstrapSupport support = new KafkaBootstrapSupport(
                container,
                sources,
                sinks,
                (config, group) -> broker.consumerClient(group),
                config -> broker.producerClient());
        support.start();
        return new TestKafkaBootstrap(support);
    }

    @Override
    public void close() {
        support.shutdown();
    }
}
