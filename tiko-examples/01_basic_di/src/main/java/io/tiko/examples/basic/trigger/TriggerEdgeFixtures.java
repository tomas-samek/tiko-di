package io.tiko.examples.basic.trigger;

import io.tiko.EventTriggerGuard;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event types and guards for the {@code @EventTrigger} edge-case tests (#162, #163). Grouped as
 * nested public types so the dozen single-purpose events stay in one place; each is driven by a
 * handler on {@link TriggerEdgeService}.
 */
public final class TriggerEdgeFixtures {

    private TriggerEdgeFixtures() {}

    // #162 — exception suppresses the downstream trigger.
    public record SourceEvent(long id) {}

    public record AsyncSourceEvent(long id) {}

    public record DownstreamEvent(long id) {}

    // #162 — mid-chain throw: A -> B (throws) -> C must not fire.
    public record ChainA(long id) {}

    public record ChainB(long id) {}

    public record ChainC(long id) {}

    // #163.A — spread edge cases, all spreading into EdgeItem.
    public record EmptySpreadEvent(long id) {}

    public record NullSpreadEvent(long id) {}

    public record MapSpreadEvent(long id) {}

    public record EdgeItem(String sku) {}

    // #163.B — guard short-circuit.
    public record ShortCircuitEvent(long id) {}

    public record ShortCircuitResult(long id) {}

    /** First guard in the AND chain; always denies, so the second must be short-circuited. */
    public static final class DenyGuard implements EventTriggerGuard {
        @Override
        public boolean shouldTrigger(Object handlerResult, Object originalEvent) {
            return false;
        }
    }

    /** Second guard; records whether it was consulted so the test can assert short-circuiting. */
    public static final class RecordingGuard implements EventTriggerGuard {
        public static final AtomicBoolean CONSULTED = new AtomicBoolean(false);

        public static void reset() {
            CONSULTED.set(false);
        }

        @Override
        public boolean shouldTrigger(Object handlerResult, Object originalEvent) {
            CONSULTED.set(true);
            return true;
        }
    }
}
