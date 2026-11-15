package io.tiko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@link Event} origin-chain API — payload/origin access, chain traversal, type lookup,
 * depth, root, and identity. {@code Event<T>} is the public wrapper handlers receive for tracing
 * event lineage, so its chain semantics are worth nailing down.
 */
class EventTest {

    private record Created(long id) {}

    private record Validated(long id) {}

    private record Paid(long id) {}

    @Test
    void rootEventHasNoOriginAndZeroDepth() {
        Event<Created> root = new Event<>(new Created(1));

        assertThat(root.getPayload()).isEqualTo(new Created(1));
        assertThat(root.getOrigin()).isEmpty();
        assertThat(root.getChainDepth()).isZero();
        assertThat(root.getRoot()).isSameAs(root);
        assertThat(root.getEventId()).isNotBlank();
        assertThat(root.getTimestamp()).isNotNull();
        assertThat(root.getOriginChain()).containsExactly(new Created(1));
    }

    @Test
    void chainedEventExposesFullLineageRootFirst() {
        Event<Created> created = new Event<>(new Created(7));
        Event<Validated> validated = new Event<>(new Validated(7), created);
        Event<Paid> paid = new Event<>(new Paid(7), validated);

        assertThat(paid.getChainDepth()).isEqualTo(2);
        assertThat(paid.getRoot()).isSameAs(created);
        assertThat(paid.getOrigin()).contains(validated);
        assertThat(paid.getOriginChain()).containsExactly(new Created(7), new Validated(7), new Paid(7));
    }

    @Test
    void findInChainReturnsFirstMatchOrEmpty() {
        Event<Created> created = new Event<>(new Created(3));
        Event<Paid> paid = new Event<>(new Paid(3), created);

        assertThat(paid.findInChain(Created.class)).contains(new Created(3));
        assertThat(paid.findInChain(Paid.class)).contains(new Paid(3));
        assertThat(paid.findInChain(Validated.class)).isEmpty();
    }

    private record Tick(int n) {}

    /** A chain of {@code hops} re-publishes on top of {@code Tick(0)}; the last wrapper holds {@code Tick(hops)}. */
    private static Event<?> republished(int hops) {
        Event<?> event = new Event<>(new Tick(0));
        for (int n = 1; n <= hops; n++) {
            event = new Event<>(new Tick(n), event);
        }
        return event;
    }

    /** #730: up to the bound nothing is dropped and the wrappers are the ones that were linked. */
    @Test
    void chainWithinTheBoundIsKeptAsItWasBuilt() {
        Event<Tick> root = new Event<>(new Tick(0));
        Event<?> event = root;
        for (int n = 1; n <= Event.MAX_ANCESTORS; n++) {
            event = new Event<>(new Tick(n), event);
        }

        assertThat(event.getChainDepth()).isEqualTo(Event.MAX_ANCESTORS);
        assertThat(event.getRoot()).isSameAs(root);
        assertThat(event.getOriginChain()).hasSize(Event.MAX_ANCESTORS + 1).startsWith(new Tick(0));
    }

    /** #730: a longer chain keeps its most recent ancestors; the older ones drop off. */
    @Test
    void chainLongerThanTheBoundKeepsItsMostRecentAncestors() {
        Event<?> event = republished(200);

        assertThat(event.getChainDepth()).isEqualTo(Event.MAX_ANCESTORS);
        assertThat(event.getOriginChain())
                .hasSize(Event.MAX_ANCESTORS + 1)
                .startsWith(new Tick(200 - Event.MAX_ANCESTORS))
                .endsWith(new Tick(200));
        assertThat(event.getRoot().getPayload()).isEqualTo(new Tick(200 - Event.MAX_ANCESTORS));
        assertThat(event.getRoot().getOrigin()).isEmpty();
    }

    /** #730: an ancestor kept in a long chain is still the event it was: same id, time and payload. */
    @Test
    void ancestorOfALongChainStillEqualsTheEventItWas() {
        Event<?> parent = republished(150);
        Event<Tick> child = new Event<>(new Tick(151), parent);

        assertThat(child.getOrigin()).contains(parent);
        Event<?> kept = child.getOrigin().orElseThrow();
        assertThat(kept.getEventId()).isEqualTo(parent.getEventId());
        assertThat(kept.getTimestamp()).isEqualTo(parent.getTimestamp());
        assertThat(kept.getPayload()).isEqualTo(new Tick(150));
    }

    /** #730: the chain of a long-running re-publish loop is read without recursion. */
    @Test
    void veryLongRepublishLoopIsReadWithoutOverflowingTheStack() {
        Event<?> event = republished(100_000);

        assertThat(event.getOriginChain()).hasSize(Event.MAX_ANCESTORS + 1).endsWith(new Tick(100_000));
        assertThat(event.findInChain(Tick.class)).contains(new Tick(100_000 - Event.MAX_ANCESTORS));
        assertThat(event.findInChain(Created.class)).isEmpty();
    }

    @Test
    void nullPayloadIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new Event<>(null)).withMessageContaining("payload");
    }

    @Test
    void identityIsByEventId() {
        Event<Created> a = new Event<>(new Created(1));
        Event<Created> b = new Event<>(new Created(1)); // same payload, distinct (random) event id

        // Same type, different (random) eventId — exercises the eventId-based comparison via the
        // dedicated AssertJ assertion. The boolean form assertThat(a.equals(b)).isFalse() trips S5838,
        // and a self/dissimilar-type isEqualTo would trip S5863/S5845.
        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(java.util.Objects.hash(a.getEventId()));
    }

    @Test
    void toStringIncludesPayloadTypeDepthAndId() {
        Event<Created> created = new Event<>(new Created(1));
        Event<Paid> paid = new Event<>(new Paid(1), created);

        assertThat(paid.toString()).contains("Paid").contains("depth=1").contains(paid.getEventId());
    }
}
