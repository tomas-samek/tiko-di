package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.events.RequestEndingEvent;
import io.tiko.runtime.Tiko;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestScopePreDestroyTest {

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void predestroy_fires_for_each_request_scoped_bean() {
        Container container = Tiko.create();
        try {
            container.runInRequestScope(() -> {
                container.get(LifoRequestA.class);
            });
        } finally {
            container.shutdown();
        }

        assertThat(TeardownRecorder.order)
                .as("REQUEST-scoped beans must run their @PreDestroy at scope exit")
                .contains("RequestA", "RequestB", "RequestC");
    }

    @Test
    void destroy_order_is_reverse_creation_lifo() {
        Container container = Tiko.create();
        try {
            container.runInRequestScope(() -> {
                // Touching A pulls in B which pulls in C → creation order: C, B, A.
                container.get(LifoRequestA.class);
            });
        } finally {
            container.shutdown();
        }

        // LIFO: last-created destroyed first. A → B → C.
        java.util.List<String> requestEntries = TeardownRecorder.order.stream()
                .filter(s -> s.startsWith("Request"))
                .toList();
        assertThat(requestEntries).containsExactly("RequestA", "RequestB", "RequestC");
    }

    @Test
    void ending_event_published_before_predestroy() {
        Container container = Tiko.create();
        AtomicInteger endingEventIndex = new AtomicInteger(-1);
        container
                .getEventBus()
                .subscribe(RequestEndingEvent.class, e -> endingEventIndex.set(TeardownRecorder.order.size()));

        try {
            container.runInRequestScope(() -> {
                container.get(LifoRequestA.class);
            });
        } finally {
            container.shutdown();
        }

        // Ending event handler ran before any @PreDestroy → recorded index is 0
        // (no Request* entries yet at that moment).
        assertThat(endingEventIndex.get()).isZero();
    }

    @Test
    void exception_in_predestroy_does_not_skip_other_beans() {
        Container container = Tiko.create();
        try {
            container.runInRequestScope(() -> {
                container.get(LifoRequestA.class);
                container.get(ThrowingPreDestroyRequestBean.class);
            });
        } finally {
            container.shutdown();
        }

        assertThat(TeardownRecorder.order)
                .as("Throwing @PreDestroy must not skip the other beans")
                .contains("RequestA", "RequestB", "RequestC", "Throwing.boom");
    }

    @Test
    void scope_map_is_cleared_after_teardown() {
        Container container = Tiko.create();
        LifoRequestA inFirst;
        LifoRequestA inSecond;
        try {
            inFirst = container.supplyInRequestScope(() -> container.get(LifoRequestA.class));
            inSecond = container.supplyInRequestScope(() -> container.get(LifoRequestA.class));
        } finally {
            container.shutdown();
        }

        assertThat(inFirst).isNotSameAs(inSecond);
    }
}
