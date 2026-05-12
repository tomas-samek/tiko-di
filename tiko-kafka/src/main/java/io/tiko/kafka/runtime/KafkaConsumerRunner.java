package io.tiko.kafka.runtime;

/**
 * Drives the consume loop for one or more {@code @KafkaSource} bindings. Sealed so future
 * threading strategies (shared consumer pool, virtual-thread runner) plug in as new
 * permits without changing the bootstrap. MVP ships {@link ThreadPerTopicRunner} only.
 */
public sealed interface KafkaConsumerRunner permits ThreadPerTopicRunner {

    void start();

    /**
     * Stop the consume loop. Should be safe to call multiple times; the runner must
     * release its client even if {@link #start()} was never invoked.
     */
    void stop();
}
