package io.tiko.examples.basic.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Pins the documented lifecycle-ordering contracts (#167):
 *
 * <ol>
 *   <li>{@code ApplicationStartedEvent} fires after all component {@code @PostConstruct} complete.
 *   <li>{@code RequestStarted}/{@code EventStarted} fire before any user handler in that scope.
 *   <li>{@code RequestEnding}/{@code EventEnding} fire after synchronous user handlers complete.
 * </ol>
 *
 * <p>It also pins the semantics surfaced while writing these: an {@code @EventHandler(async=true)}
 * handler is <em>detached</em> from the triggering scope, so scope exit does not await it. Draining
 * async work at scope exit would contradict "async" — the scope is a synchronous frame, and async
 * dispatch is the in-process equivalent of a distributed consumer running in its own scope. The
 * deeper guard (rejecting REQUEST-scoped dependencies in async handlers) is tracked for Phase 7.
 */
class LifecycleOrderingTest {

    @Test
    void applicationStartedFiresAfterAllPostConstruct() {
        try (Container container = Tiko.create()) {
            List<String> log = container.get(OrderLog.class).snapshot();

            assertThat(log).contains("PC:Eager", "PC:Probe", "APP_STARTED");
            int appStarted = log.indexOf("APP_STARTED");
            assertThat(appStarted)
                    .as("ApplicationStartedEvent fires after every @PostConstruct")
                    .isGreaterThan(log.indexOf("PC:Eager"))
                    .isGreaterThan(log.indexOf("PC:Probe"));
        }
    }

    @Test
    void requestStartedFiresBeforeUserHandler() {
        try (Container container = Tiko.create()) {
            OrderLog log = container.get(OrderLog.class);
            container.runInRequestScope(() -> container.getEventBus().publish(new Ping()));

            List<String> snap = log.snapshot();
            assertThat(snap.indexOf("REQ_START"))
                    .as("RequestStartedEvent fires before any user handler in the scope")
                    .isLessThan(snap.indexOf("PING_HANDLED"));
        }
    }

    @Test
    void eventStartedFiresBeforeUserHandler() {
        try (Container container = Tiko.create()) {
            OrderLog log = container.get(OrderLog.class);
            container.runInEventScope(() -> container.getEventBus().publish(new Ping()));

            List<String> snap = log.snapshot();
            assertThat(snap.indexOf("EVT_START"))
                    .as("EventStartedEvent fires before any user handler in the scope")
                    .isLessThan(snap.indexOf("PING_HANDLED"));
        }
    }

    @Test
    void requestEndingFiresAfterSyncHandler() {
        try (Container container = Tiko.create()) {
            OrderLog log = container.get(OrderLog.class);
            container.runInRequestScope(() -> container.getEventBus().publish(new Ping()));

            List<String> snap = log.snapshot();
            assertThat(snap.indexOf("PING_HANDLED"))
                    .as("RequestEndingEvent fires after synchronous user handlers complete")
                    .isLessThan(snap.indexOf("REQ_END"));
        }
    }

    @Test
    void eventEndingFiresAfterSyncHandler() {
        try (Container container = Tiko.create()) {
            OrderLog log = container.get(OrderLog.class);
            container.runInEventScope(() -> container.getEventBus().publish(new Ping()));

            List<String> snap = log.snapshot();
            assertThat(snap.indexOf("PING_HANDLED"))
                    .as("EventEndingEvent fires after synchronous user handlers complete")
                    .isLessThan(snap.indexOf("EVT_END"));
        }
    }

    @Test
    void asyncHandlerIsDetachedAndNotAwaitedAtScopeExit() throws InterruptedException {
        try (Container container = Tiko.create()) {
            OrderLog log = container.get(OrderLog.class);
            AsyncProbe asyncProbe = container.get(AsyncProbe.class);

            container.runInRequestScope(() -> container.getEventBus().publish(new AsyncPing()));

            // RequestEnding fired in the scope's finally without awaiting the gated async handler:
            // the scope is a synchronous frame, and async dispatch detaches from it.
            assertThat(log.snapshot()).contains("REQ_END");
            assertThat(log.snapshot())
                    .as("async handler is detached — scope exit must not await it")
                    .doesNotContain("ASYNC_HANDLED");

            asyncProbe.openGate();
            assertThat(asyncProbe.awaitDone(2, TimeUnit.SECONDS))
                    .as("async handler completes after the scope has already ended")
                    .isTrue();
            assertThat(log.snapshot()).contains("ASYNC_HANDLED");
        }
    }
}
