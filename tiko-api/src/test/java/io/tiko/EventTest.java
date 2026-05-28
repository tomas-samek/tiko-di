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

    @Test
    void nullPayloadIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new Event<>(null)).withMessageContaining("payload");
    }

    @Test
    void identityIsByEventId() {
        Event<Created> a = new Event<>(new Created(1));
        Event<Created> b = new Event<>(new Created(1)); // same payload, distinct (random) event id
        Object notAnEvent = "x";

        // Direct boolean form, not assertThat(a).isEqualTo(...): self/dissimilar-type AssertJ
        // comparisons are themselves flagged as reliability bugs (S5863/S5845).
        assertThat(a.equals(b)).as("distinct event ids -> not equal").isFalse();
        assertThat(a.equals(notAnEvent)).as("non-Event -> not equal").isFalse();
        assertThat(a.equals(null)).isFalse();
        assertThat(a.hashCode()).isEqualTo(java.util.Objects.hash(a.getEventId()));
    }

    @Test
    void toStringIncludesPayloadTypeDepthAndId() {
        Event<Created> created = new Event<>(new Created(1));
        Event<Paid> paid = new Event<>(new Paid(1), created);

        assertThat(paid.toString()).contains("Paid").contains("depth=1").contains(paid.getEventId());
    }
}
