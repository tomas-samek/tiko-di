package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AggregatingContainer}'s accessor, scope-delegation, and lifecycle surface (#236) —
 * the bits {@code AggregatingContainerLookupTest} (which focuses on lookup-miss exceptions) doesn't
 * exercise. Backed by the no-op {@link StubContainer} registered via
 * {@code src/test/resources/META-INF/tiko/container.properties}.
 */
class AggregatingContainerDelegationTest {

    private static AggregatingContainer newContainer(io.tiko.EventBus bus, ErrorHandler handler) {
        return new AggregatingContainer(bus, handler, null, Duration.ofSeconds(1));
    }

    @Test
    void accessorsReturnTheSuppliedCollaborators() {
        LocalEventBus bus = new LocalEventBus();
        ErrorHandler handler = ctx -> {};
        try (AggregatingContainer c = newContainer(bus, handler)) {
            assertThat(c.getEventBus()).isSameAs(bus);
            assertThat(c.getErrorHandler()).isSameAs(handler);
            assertThat(c.getEventExecutor()).isNotNull();
        }
    }

    @Test
    void getAllReturnsEmptyUnmodifiableListWhenNothingRegistered() {
        try (AggregatingContainer c = newContainer(new LocalEventBus(), ctx -> {})) {
            assertThat(c.getAll(String.class)).isEmpty();
        }
    }

    @Test
    void scopeHelpersRunTheBodyExactlyOnceAndReturnTheValue() {
        try (AggregatingContainer c = newContainer(new LocalEventBus(), ctx -> {})) {
            AtomicBoolean requestRan = new AtomicBoolean(false);
            AtomicBoolean eventRan = new AtomicBoolean(false);

            c.runInRequestScope(() -> requestRan.set(true));
            c.runInEventScope(() -> eventRan.set(true));

            assertThat(requestRan).isTrue();
            assertThat(eventRan).isTrue();
            assertThat(c.supplyInRequestScope(() -> "req")).isEqualTo("req");
            assertThat(c.supplyInEventScope(() -> "evt")).isEqualTo("evt");
        }
    }

    @Test
    void startAndShutdownAreIdempotentAndDoNotThrow() {
        AggregatingContainer c = newContainer(new LocalEventBus(), ctx -> {});
        assertThatCode(() -> {
                    c.start();
                    c.start(); // idempotent
                    c.shutdown();
                    c.shutdown(); // idempotent
                })
                .doesNotThrowAnyException();
    }

    @Test
    void getAllReturnedListIsUnmodifiable() {
        try (Container c = newContainer(new LocalEventBus(), ctx -> {})) {
            var all = c.getAll(String.class);
            assertThatCode(() -> all.add("x")).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
