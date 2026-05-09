package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.events.ApplicationEndingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EndingEventBehaviourTest {

    @BeforeEach
    void resetCounter() {
        ShutdownTestCounter.reset();
    }

    @Test
    void throwing_ending_event_handler_does_not_skip_predestroy() {
        Container container = Tiko.create();
        container.getEventBus().subscribe(ApplicationEndingEvent.class,
            e -> { throw new IllegalStateException("handler boom"); });
        container.get(ShutdownTestCounter.class); // ensure it's in the singleton map

        container.shutdown();

        assertThat(ShutdownTestCounter.preDestroyCount.get())
            .as("@PreDestroy must run even when an ApplicationEndingEvent handler throws")
            .isEqualTo(1);
    }

    @Test
    void ending_event_handler_can_call_container_get() {
        AtomicBoolean handlerRan = new AtomicBoolean(false);
        AtomicReference<Object> retrieved = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Container container = Tiko.create();
        container.getEventBus().subscribe(ApplicationEndingEvent.class, e -> {
            handlerRan.set(true);
            try {
                retrieved.set(container.get(ShutdownTestCounter.class));
            } catch (Throwable t) {
                error.set(t);
            }
        });

        container.shutdown();

        assertThat(handlerRan).isTrue();
        assertThat(error.get())
            .as("get() must succeed during ApplicationEndingEvent — stopped flag is set after publish")
            .isNull();
        assertThat(retrieved.get()).isInstanceOf(ShutdownTestCounter.class);
    }
}
