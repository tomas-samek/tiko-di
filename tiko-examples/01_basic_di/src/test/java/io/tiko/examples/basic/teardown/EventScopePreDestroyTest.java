package io.tiko.examples.basic.teardown;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.events.EventEndingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EventScopePreDestroyTest {

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void predestroy_fires_in_lifo_order_for_each_event_scoped_bean() {
        Container container = Tiko.create();
        try {
            container.runInEventScope(() -> {
                container.get(LifoEventA.class);
            });
        } finally {
            container.shutdown();
        }

        java.util.List<String> eventEntries = TeardownRecorder.order.stream()
            .filter(s -> s.startsWith("Event"))
            .toList();
        assertThat(eventEntries).containsExactly("EventA", "EventB", "EventC");
    }

    @Test
    void ending_event_published_before_predestroy() {
        Container container = Tiko.create();
        AtomicInteger endingEventIndex = new AtomicInteger(-1);
        container.getEventBus().subscribe(EventEndingEvent.class, e ->
            endingEventIndex.set(TeardownRecorder.order.size())
        );

        try {
            container.runInEventScope(() -> {
                container.get(LifoEventA.class);
            });
        } finally {
            container.shutdown();
        }

        assertThat(endingEventIndex.get()).isZero();
    }

    @Test
    void multiple_event_scopes_inside_one_request_each_run_their_own_teardown() {
        Container container = Tiko.create();
        try {
            container.runInRequestScope(() -> {
                for (int i = 0; i < 3; i++) {
                    container.runInEventScope(() -> container.get(LifoEventC.class));
                }
            });
        } finally {
            container.shutdown();
        }

        long eventCDestroys = TeardownRecorder.order.stream()
            .filter(s -> s.equals("EventC"))
            .count();
        assertThat(eventCDestroys).isEqualTo(3);
    }
}
