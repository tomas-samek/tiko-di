package io.tiko;

/**
 * Transport-neutral serializer SPI (#119): turns an event object into bytes for the wire and back.
 * Every first-party transport adapter ({@code tiko-kafka}, future {@code tiko-rabbitmq} /
 * {@code tiko-jms}) consumes this one surface, so a serializer written once works across transports
 * and users can swap implementations (JSON, Avro, Protobuf, ...) without forking an adapter.
 *
 * <p>Lives in {@code tiko-api} so transport modules depend on it without pulling {@code tiko-runtime}.
 * The interface is intentionally <em>not</em> parameterized at the class level: a single serializer
 * typically handles many payload types (JSON via Jackson; Avro via a schema registry). Each call
 * carries its own target type via {@link #deserialize}'s method-level type parameter, so one impl can
 * answer for every event class rather than one impl per type.
 *
 * <p>Implementations must be <strong>thread-safe</strong> — a single instance serves concurrent
 * dispatch across transport threads.
 */
public interface EventSerializer {

    /** Serialize {@code value} to bytes. Thread-safe. */
    byte[] serialize(Object value);

    /** Deserialize {@code bytes} into an instance of {@code type}. Thread-safe. */
    <T> T deserialize(byte[] bytes, Class<T> type);
}
