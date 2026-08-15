package io.tiko.examples.events;

import io.tiko.Event;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Order processing pipeline expressed entirely with {@code @EventHandler} +
 * {@code @EventTrigger}. The container takes care of routing — handlers only return
 * the next stage's payload.
 *
 * <p>Pipeline (each arrow is a trigger publishing the handler's return value):
 * <pre>
 *   OrderPlaced  -&gt;  onPlaced()      -&gt;  OrderValidated
 *                                           |
 *                                           v  (ValidOrderGuard: only when valid)
 *                                       onValidated()   -&gt;  OrderShipped
 *                                                              |
 *                                                              v
 *                                                          onShipped()  (prints chain)
 * </pre>
 *
 * <p>{@link BatchSubmitted} demonstrates spread: one envelope produces N
 * {@link OrderPlaced} events, each of which then runs the pipeline above.
 */
@Component(scope = Scope.SINGLETON)
public class OrderWorkflow {

    /** Validate the order; the return value becomes the next event's payload. */
    @EventHandler
    @EventTrigger(eventName = "OrderValidated")
    public OrderValidated onPlaced(OrderPlaced event) {
        boolean valid = event.total() > 0;
        System.out.printf(
                java.util.Locale.ROOT,
                "  validate %s: total=%.2f -> %s%n",
                event.orderId(),
                event.total(),
                valid ? "VALID" : "INVALID");
        return new OrderValidated(event.orderId(), event.customer(), event.total(), valid);
    }

    /** Ship — but only when the guard allows it (i.e. validation passed). */
    @EventHandler
    @EventTrigger(eventName = "OrderShipped", guard = ValidOrderGuard.class)
    public OrderShipped onValidated(OrderValidated event) {
        return new OrderShipped(event.orderId(), event.customer(), event.total());
    }

    /**
     * Final stage — the {@link Event} parameter exposes the full chain so a single
     * handler can trace the lineage of the event without any explicit context plumbing.
     */
    @EventHandler
    public void onShipped(OrderShipped event, Event<?> wrapper) {
        String chain = wrapper.getOriginChain().stream()
                .map(o -> o.getClass().getSimpleName())
                .collect(Collectors.joining(" -> "));
        System.out.printf(
                java.util.Locale.ROOT, "  ship %s for %s ($%.2f)%n", event.orderId(), event.customer(), event.total());
        System.out.println("    chain: " + chain);
    }

    /**
     * Two {@code @EventTrigger} annotations on one handler — the repeatable form the
     * compiler wraps in the {@code @EventTriggers} container. The return value is
     * published <em>once per trigger</em>, so {@link #onNotice} below runs twice.
     *
     * <p>Both publishes reach the same handler because routing is keyed by the payload
     * <em>type</em>: {@code eventName} is a trace / topology label, never a routing key.
     * That is why naming the two triggers differently changes what a topology view shows
     * but not which handlers fire — worth knowing before reaching for this to fan work
     * out to two different consumers, which is not what it does.
     */
    @EventHandler
    @EventTrigger(eventName = "ShipmentNotice.customer")
    @EventTrigger(eventName = "ShipmentNotice.warehouse")
    public ShipmentNotice onShippedFanOut(OrderShipped event) {
        return new ShipmentNotice(event.orderId(), event.customer());
    }

    /**
     * Consumes the fan-out notice and uses {@link Event#findInChain(Class)} to recover the
     * event that started the pipeline, without threading it through every intermediate
     * payload. {@code getOriginChain()} (see {@link #onShipped}) gives you the whole
     * lineage; {@code findInChain} answers "was there an X back there, and what was it?".
     */
    @EventHandler
    public void onNotice(ShipmentNotice notice, Event<?> wrapper) {
        String origin = wrapper.findInChain(OrderPlaced.class)
                .map(placed -> String.format(java.util.Locale.ROOT, "%.2f", placed.total()))
                .orElse("<not in chain>");
        System.out.printf(
                java.util.Locale.ROOT,
                "    notice for %s (%s) -> original order total: %s%n",
                notice.orderId(),
                notice.customer(),
                origin);
    }

    /** Spread: a batch envelope fans out into individual {@link OrderPlaced} events. */
    @EventHandler
    @EventTrigger(eventName = "OrderPlaced", spread = true)
    public List<OrderPlaced> onBatch(BatchSubmitted batch) {
        System.out.println("  batch received with " + batch.orders().size() + " orders, fanning out");
        return batch.orders();
    }
}
