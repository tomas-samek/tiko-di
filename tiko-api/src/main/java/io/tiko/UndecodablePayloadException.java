package io.tiko;

/**
 * Thrown by an {@link EventSerializer#deserialize} implementation when the bytes can
 * <strong>never</strong> be decoded into the target type: malformed input, a type mismatch, a
 * missing required field (#549). The failure is deterministic, so redelivering the same bytes fails
 * the same way every time. A transport's error policy can therefore skip or dead-letter such a
 * record without retrying it, e.g. {@code error.cause() instanceof UndecodablePayloadException} in a
 * Kafka ingest decider.
 *
 * <p>A serializer throws something else for a failure that may succeed on retry, such as an
 * unreachable schema registry. Anything other than this type means "possibly transient".
 *
 * <p>Carries the target type and a message, and deliberately <em>no cause</em>: a parser's own
 * exception usually quotes the offending input, and error handlers log the whole cause chain, so
 * the message must not contain payload-derived values either (SEC-4). Extends
 * {@link IllegalStateException} so existing catch sites keep matching.
 */
public final class UndecodablePayloadException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient Class<?> targetType;

    /**
     * @param targetType the type the bytes were to be decoded into
     * @param message    what failed, without any payload-derived value
     */
    public UndecodablePayloadException(Class<?> targetType, String message) {
        super(message);
        this.targetType = targetType;
    }

    /** The type the bytes were to be decoded into. */
    public Class<?> targetType() {
        return targetType;
    }
}
