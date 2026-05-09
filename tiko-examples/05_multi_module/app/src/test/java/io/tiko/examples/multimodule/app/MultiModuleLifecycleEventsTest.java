package io.tiko.examples.multimodule.app;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.events.ApplicationEndingEvent;
import io.tiko.events.ApplicationStartedEvent;
import io.tiko.runtime.AggregatingContainer;
import io.tiko.runtime.LocalEventBus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #45 verification for multi-module: {@code ApplicationStartedEvent} fires exactly
 * once and {@code ApplicationEndingEvent} fires exactly once across the whole
 * aggregator, not once per per-module container.
 *
 * <p>This test bypasses {@code Tiko.create()} so it can subscribe to the events
 * <em>before</em> calling {@code start()} — the lifecycle event publish happens
 * inside {@code start()}, and any subscriber added after that publish would miss
 * the event. {@code Tiko.create()} calls {@code start()} internally, so a
 * programmatic subscriber attached after {@code Tiko.create()} returns can only
 * verify the shutdown-side event.
 */
class MultiModuleLifecycleEventsTest {

    @Test
    void aggregating_container_publishes_application_started_event_exactly_once() throws Exception {
        EventBus eventBus = newLocalEventBus();
        AtomicInteger startedCount = new AtomicInteger(0);
        eventBus.subscribe(ApplicationStartedEvent.class, e -> startedCount.incrementAndGet());

        Container container = new AggregatingContainer(eventBus, ctx -> {}, null);
        try {
            container.start();
            container.start(); // idempotent — second call must not republish
        } finally {
            container.shutdown();
        }

        assertThat(startedCount.get())
            .as("ApplicationStartedEvent must fire exactly once across two start() calls")
            .isEqualTo(1);
    }

    @Test
    void aggregating_container_publishes_application_ending_event_exactly_once() throws Exception {
        EventBus eventBus = newLocalEventBus();
        AtomicInteger endingCount = new AtomicInteger(0);
        eventBus.subscribe(ApplicationEndingEvent.class, e -> endingCount.incrementAndGet());

        Container container = new AggregatingContainer(eventBus, ctx -> {}, null);
        container.start();
        container.shutdown();
        container.shutdown(); // idempotent

        assertThat(endingCount.get())
            .as("ApplicationEndingEvent must fire exactly once across module-a and module-b "
                + "(N=2 modules) and across two shutdown() calls")
            .isEqualTo(1);
    }

    private EventBus newLocalEventBus() {
        return new LocalEventBus();
    }
}
