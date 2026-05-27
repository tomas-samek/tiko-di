package io.tiko.examples.basic.trigger;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.AsyncSourceEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ChainA;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ChainB;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ChainC;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.DenyGuard;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.DownstreamEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.EdgeItem;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.EmptySpreadEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.MapSpreadEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.NullSpreadEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.RecordingGuard;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ShortCircuitEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ShortCircuitResult;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.SourceEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Handlers for the {@code @EventTrigger} edge-case tests (#162, #163): throwing handlers that must
 * suppress their trigger, spread edge cases, and a short-circuiting guard chain. Keeps its own
 * {@code received} log so assertions are independent of {@link OrderTriggerService}.
 */
@Component(scope = Scope.SINGLETON)
public class TriggerEdgeService {

    private final List<Object> received = new CopyOnWriteArrayList<>();

    // ─── #162: exception suppresses the downstream trigger ──────────────────────

    @EventHandler
    @EventTrigger(eventName = "DownstreamEvent")
    public DownstreamEvent onSource(SourceEvent event) {
        received.add(event);
        throw new RuntimeException("boom");
    }

    @EventHandler
    @EventTrigger(eventName = "DownstreamEvent", async = true)
    public DownstreamEvent onAsyncSource(AsyncSourceEvent event) {
        received.add(event);
        throw new RuntimeException("boom-async");
    }

    @EventHandler
    public void onDownstream(DownstreamEvent event) {
        received.add(event);
    }

    // ─── #162: mid-chain throw — A -> B (throws) -> C must not fire ──────────────

    @EventHandler
    @EventTrigger(eventName = "ChainB")
    public ChainB onChainA(ChainA event) {
        received.add(event);
        return new ChainB(event.id());
    }

    @EventHandler
    @EventTrigger(eventName = "ChainC")
    public ChainC onChainB(ChainB event) {
        received.add(event);
        throw new RuntimeException("mid-chain boom");
    }

    @EventHandler
    public void onChainC(ChainC event) {
        received.add(event);
    }

    // ─── #163.A: spread edge cases ──────────────────────────────────────────────

    @EventHandler
    @EventTrigger(eventName = "EdgeItem", spread = true)
    public List<EdgeItem> onEmptySpread(EmptySpreadEvent event) {
        received.add(event);
        return List.of();
    }

    @EventHandler
    @EventTrigger(eventName = "EdgeItem", spread = true)
    public List<EdgeItem> onNullSpread(NullSpreadEvent event) {
        received.add(event);
        return Arrays.asList(new EdgeItem("X"), null, new EdgeItem("Y"));
    }

    @EventHandler
    @EventTrigger(eventName = "EdgeItem", spread = true)
    public Map<String, EdgeItem> onMapSpread(MapSpreadEvent event) {
        received.add(event);
        return Map.of("only", new EdgeItem("Z"));
    }

    @EventHandler
    public void onEdgeItem(EdgeItem event) {
        received.add(event);
    }

    // ─── #163.B: guard chain short-circuits in source order ─────────────────────

    @EventHandler
    @EventTrigger(
            eventName = "ShortCircuitResult",
            guard = {DenyGuard.class, RecordingGuard.class})
    public ShortCircuitResult onShortCircuit(ShortCircuitEvent event) {
        received.add(event);
        return new ShortCircuitResult(event.id());
    }

    @EventHandler
    public void onShortCircuitResult(ShortCircuitResult event) {
        received.add(event);
    }

    public List<Object> getReceived() {
        return List.copyOf(received);
    }
}
