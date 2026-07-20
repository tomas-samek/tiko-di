package io.tiko.examples.multimodule.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.events.ApplicationEndingEvent;
import io.tiko.events.ApplicationStartedEvent;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.examples.multimodule.modulea.UserCreatedEvent;
import io.tiko.runtime.AggregatingContainer;
import io.tiko.runtime.LocalEventBus;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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
    void aggregating_container_publishes_application_started_event_exactly_once() {
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
    void aggregating_container_publishes_application_ending_event_exactly_once() {
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

    @Test
    void unitOfWorkPublishesExactlyOneLifecyclePairAcrossModules() {
        // #339: one runInEventScope must publish ONE EventStarted/EventEnding pair with one
        // eventId — not one pair per nested module frame (N=2 modules here).
        EventBus eventBus = newLocalEventBus();
        List<EventStartedEvent> started = new CopyOnWriteArrayList<>();
        List<EventEndingEvent> ending = new CopyOnWriteArrayList<>();
        eventBus.subscribe(EventStartedEvent.class, started::add);
        eventBus.subscribe(EventEndingEvent.class, ending::add);

        Container container = new AggregatingContainer(eventBus, ctx -> {}, null);
        try {
            container.start();
            container.runInEventScope(() -> {});
        } finally {
            container.shutdown();
        }

        assertThat(started).hasSize(1);
        assertThat(ending).hasSize(1);
        assertThat(ending.get(0).eventId()).isEqualTo(started.get(0).eventId());
    }

    @Test
    void supplyInEventScopePublishesExactlyOneLifecyclePairAcrossModules() {
        EventBus eventBus = newLocalEventBus();
        List<EventStartedEvent> started = new CopyOnWriteArrayList<>();
        List<EventEndingEvent> ending = new CopyOnWriteArrayList<>();
        eventBus.subscribe(EventStartedEvent.class, started::add);
        eventBus.subscribe(EventEndingEvent.class, ending::add);

        Container container = new AggregatingContainer(eventBus, ctx -> {}, null);
        try {
            container.start();
            assertThat(container.supplyInEventScope(() -> "value")).isEqualTo("value");
        } finally {
            container.shutdown();
        }

        assertThat(started).hasSize(1);
        assertThat(ending).hasSize(1);
        assertThat(ending.get(0).eventId()).isEqualTo(started.get(0).eventId());
    }

    @Test
    void asyncHandlerDispatchPublishesExactlyOneLifecyclePairAcrossModules() throws InterruptedException {
        // #433: async @EventHandler dispatch under an aggregator opens its own EVENT unit on a
        // per-module container built with publishLifecycleEvents=false. The aggregator is not on
        // the async dispatch path (generated dispatch calls the module container directly), so the
        // module container is the sole publisher of the detached unit's pair — exactly one
        // EventStarted/EventEnding pair, one eventId, must reach the shared bus.
        EventBus eventBus = newLocalEventBus();
        List<EventStartedEvent> started = new CopyOnWriteArrayList<>();
        List<EventEndingEvent> ending = new CopyOnWriteArrayList<>();
        CountDownLatch pairComplete = new CountDownLatch(1);
        eventBus.subscribe(EventStartedEvent.class, started::add);
        eventBus.subscribe(EventEndingEvent.class, e -> {
            ending.add(e);
            pairComplete.countDown();
        });

        Container container = new AggregatingContainer(eventBus, ctx -> {}, null);
        try {
            container.start();
            // AsyncUserAuditor (module-a) handles this async → detached EVENT unit on the executor.
            eventBus.publish(new UserCreatedEvent(1L, "Alice", "alice@example.com"));
            assertThat(pairComplete.await(5, TimeUnit.SECONDS))
                    .as("async detached unit must complete and publish its EventEndingEvent")
                    .isTrue();
        } finally {
            container.shutdown();
        }

        assertThat(started).hasSize(1);
        assertThat(ending).hasSize(1);
        assertThat(ending.get(0).eventId()).isEqualTo(started.get(0).eventId());
    }

    private EventBus newLocalEventBus() {
        return new LocalEventBus();
    }
}
