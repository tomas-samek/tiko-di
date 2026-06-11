package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.Subscription;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A throwing {@code EventStartedEvent}/{@code EventEndingEvent} publish must not poison the
 * unit-of-work bracket (#336): the task still runs, EVENT-scoped teardown still fires, and the
 * thread can open subsequent units. Reproduced via a bus decorator whose publish throws for
 * unit-lifecycle events — the same effect an {@code Error} escaping a sync handler would have.
 */
class EventScopeLifecyclePublishFailureTest {

    /** Forwards everything except unit-lifecycle publishes, which throw. */
    private static final class ThrowingLifecycleBus implements EventBus {
        private final EventBus delegate;

        ThrowingLifecycleBus(EventBus delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> void publish(T event) {
            if (event instanceof EventStartedEvent || event instanceof EventEndingEvent) {
                throw new IllegalStateException("decorator publish failure");
            }
            delegate.publish(event);
        }

        @Override
        public <T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback) {
            return delegate.subscribe(eventType, callback);
        }
    }

    private static Container createWithThrowingBus() {
        return Tiko.create(TikoOptions.builder()
                .eventBusDecorator(ThrowingLifecycleBus::new)
                .build());
    }

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void throwingLifecyclePublishDoesNotPoisonTheUnitFrame() {
        Container container = createWithThrowingBus();
        try {
            AtomicInteger ran = new AtomicInteger();

            container.runInEventScope(ran::incrementAndGet);
            // The same thread must be able to open the next unit — a poisoned frame throws here.
            container.runInEventScope(ran::incrementAndGet);

            assertThat(ran).hasValue(2);
            assertThat(container.supplyInEventScope(() -> "value")).isEqualTo("value");
        } finally {
            container.shutdown();
        }
    }

    @Test
    void eventScopedTeardownRunsWhenLifecyclePublishThrows() {
        Container container = createWithThrowingBus();
        try {
            container.runInEventScope(() -> container.get(LifoEventC.class));
        } finally {
            container.shutdown();
        }

        assertThat(TeardownRecorder.order).contains("EventC");
    }
}
