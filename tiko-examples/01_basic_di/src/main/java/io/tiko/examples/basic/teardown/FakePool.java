package io.tiko.examples.basic.teardown;

/**
 * Fake third-party closeable used by the factory-produced AutoCloseable test fixtures.
 * Stand-in for things like {@code HikariDataSource}, {@code HttpClient},
 * {@code KafkaProducer} — types the user does not own and cannot annotate, but which
 * implement {@link AutoCloseable}.
 */
public final class FakePool implements AutoCloseable {

    private final String label;

    public FakePool(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public void close() {
        TeardownRecorder.record("FakePool." + label);
    }
}
