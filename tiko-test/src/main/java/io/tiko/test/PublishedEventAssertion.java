package io.tiko.test;

import java.util.List;
import java.util.function.Predicate;

/**
 * Fluent assertion handle returned by {@link RecordingEventBus#assertPublished(Class)}.
 *
 * <p>Holds the subset of captured events that matched the asserted type so callers can chain
 * payload-level predicates ({@link #withPayload(Predicate)}) without re-querying the bus.
 *
 * <p>Each predicate check throws {@link AssertionError} if no captured event satisfies it; the
 * error message includes the captured events themselves to make the failure self-explanatory.
 */
public final class PublishedEventAssertion {

    private final Class<?> type;
    private final List<Object> matches;

    PublishedEventAssertion(Class<?> type, List<Object> matches) {
        this.type = type;
        this.matches = matches;
    }

    /**
     * Asserts that at least one captured event of the asserted type satisfies {@code predicate}.
     *
     * @return this assertion for further chaining
     * @throws AssertionError if no matching event satisfies the predicate
     */
    @SuppressWarnings("unchecked")
    public <T> PublishedEventAssertion withPayload(Predicate<T> predicate) {
        var any = matches.stream().anyMatch(e -> predicate.test((T) e));
        if (!any) {
            throw new AssertionError(
                    "No published " + type.getSimpleName() + " satisfied the payload predicate. Captured: " + matches);
        }
        return this;
    }
}
