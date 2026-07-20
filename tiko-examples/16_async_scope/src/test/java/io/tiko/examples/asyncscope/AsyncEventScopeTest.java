package io.tiko.examples.asyncscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AsyncEventScopeTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @BeforeEach
    void resetCaptures() {
        ProbeLog.reset();
        AsyncProbeHandlers.reset();
        LifecycleObserver.SEEN.set(0);
        LifecycleObserver.STARTED_TOTAL.set(0);
    }

    @Test
    void asyncHandlerGetsFreshUnitWithTeardown() {
        try (Container container = Tiko.create()) {
            container.getEventBus().publish(new AsyncProbeHandlers.Touch());
            await().atMost(WAIT).until(() -> ProbeLog.destroyedIds().size() == 1);

            assertThat(AsyncProbeHandlers.TOUCHED_IDS).hasSize(1);
            assertThat(ProbeLog.createdIds()).containsExactlyElementsOf(AsyncProbeHandlers.TOUCHED_IDS);
            assertThat(ProbeLog.destroyedIds()).containsExactlyElementsOf(ProbeLog.createdIds());
        }
    }

    @Test
    void eachRetryAttemptGetsItsOwnUnit() {
        try (Container container = Tiko.create()) {
            container.getEventBus().publish(new AsyncProbeHandlers.FlakyTouch());
            await().atMost(WAIT).until(() -> AsyncProbeHandlers.FLAKY_ATTEMPTS.get() == 3);
            await().atMost(WAIT).until(() -> ProbeLog.destroyedIds().size() == 3);

            assertThat(AsyncProbeHandlers.TOUCHED_IDS).hasSize(3).doesNotHaveDuplicates();
            assertThat(ProbeLog.destroyedIds()).containsExactlyInAnyOrderElementsOf(AsyncProbeHandlers.TOUCHED_IDS);
        }
    }

    @Test
    void asyncDispatchPublishesOneLifecyclePair() {
        try (Container container = Tiko.create()) {
            AtomicInteger started = new AtomicInteger();
            AtomicInteger ending = new AtomicInteger();
            container.getEventBus().subscribe(EventStartedEvent.class, e -> started.incrementAndGet());
            container.getEventBus().subscribe(EventEndingEvent.class, e -> ending.incrementAndGet());

            container.getEventBus().publish(new AsyncProbeHandlers.Touch());
            await().atMost(WAIT).until(() -> ending.get() >= 1);

            assertThat(started.get()).isEqualTo(1);
            assertThat(ending.get()).isEqualTo(1);
        }
    }

    @Test
    void timeoutBreachStillTearsDownTheUnit() {
        try (Container container = Tiko.create()) {
            AsyncProbeHandlers.blockGate = new java.util.concurrent.CountDownLatch(1);
            container.getEventBus().publish(new AsyncProbeHandlers.BlockedTouch());
            await().atMost(WAIT).until(() -> ProbeLog.destroyedIds().size() == 1);

            assertThat(ProbeLog.destroyedIds()).containsExactlyElementsOf(ProbeLog.createdIds());
        }
    }

    @Test
    void callerRunsDetachmentPreservesTheOuterUnit() throws Exception {
        // A user-supplied executor pins the saturation shape deterministically: one worker,
        // a queue of one, JDK CallerRunsPolicy running overflow inline on the caller. The
        // TikoOptions pool-sizing knobs now feed the single-module pool too (#435, see
        // PoolSizingKnobsTest); an explicit executor is kept here only to fix the exact
        // queue/rejection shape this detachment test depends on, independent of the framework's
        // overflow-policy wiring.
        var executor = new java.util.concurrent.ThreadPoolExecutor(
                1,
                1,
                0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1),
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        var options =
                io.tiko.runtime.TikoOptions.builder().eventExecutor(executor).build();
        try (Container container = Tiko.create(options)) {
            var workerGate = new java.util.concurrent.CountDownLatch(1);
            AsyncProbeHandlers.blockGate = workerGate;
            String testThread = Thread.currentThread().getName();

            container.runInEventScope(() -> {
                UnitProbe outer = container.get(UnitProbe.class);
                String outerBefore = outer.id();

                // Occupy the single worker (SlowTouch self-releases after 2s even if it is
                // itself caller-run inline — no interleaving can deadlock), then burst enough
                // Touches that at least one MUST overflow to CALLER_RUNS on this thread: the
                // pool holds at most worker(1) + queue(1) while 7 tasks arrive back-to-back.
                container.getEventBus().publish(new AsyncProbeHandlers.SlowTouch());
                for (int i = 0; i < 6; i++) {
                    container.getEventBus().publish(new AsyncProbeHandlers.Touch());
                }

                // Any caller-run dispatch executed INLINE on this thread, inside our open
                // unit. Detachment must have suspended and restored our frame around it.
                assertThat(outer.id()).isEqualTo(outerBefore); // outer unit intact
                workerGate.countDown();
            });

            await().atMost(WAIT).until(() -> AsyncProbeHandlers.TOUCHED_IDS.size() >= 7);
            // Proof that CALLER_RUNS actually inlined at least one dispatch on the test thread.
            assertThat(AsyncProbeHandlers.TOUCHED_THREADS).contains(testThread);
            // 7 event units + the outer unit, each with its own probe, all torn down.
            await().atMost(WAIT).until(() -> ProbeLog.destroyedIds().size() >= 8);
            assertThat(AsyncProbeHandlers.TOUCHED_IDS).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void asyncLifecycleObserverDoesNotRecurse() {
        try (Container container = Tiko.create()) {
            container.getEventBus().publish(new AsyncProbeHandlers.Touch());
            // The unit's own EventStarted/Ending fire; LifecycleObserver (async, subscribed to
            // EventStartedEvent) must dispatch WITHOUT minting a new unit — so the total count
            // of started events stays exactly 1 no matter how long we watch.
            await().atMost(WAIT).until(() -> LifecycleObserver.SEEN.get() >= 1);
            await().pollDelay(Duration.ofMillis(300)).until(() -> true);
            assertThat(LifecycleObserver.STARTED_TOTAL.get()).isEqualTo(1);
        }
    }
}
