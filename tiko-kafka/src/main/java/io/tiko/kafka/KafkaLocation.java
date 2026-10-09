package io.tiko.kafka;

/** Formats where a Kafka failure happened for {@link io.tiko.TransportError#location()} (#516). */
final class KafkaLocation {

    private KafkaLocation() {}

    /** {@code "orders-3@1042"}; just the topic when there is no record (a poll failure, partition -1). */
    static String of(String topic, int partition, long offset) {
        return partition < 0 ? topic : topic + "-" + partition + "@" + offset;
    }
}
