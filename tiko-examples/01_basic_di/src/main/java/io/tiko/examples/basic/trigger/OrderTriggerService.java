package io.tiko.examples.basic.trigger;

import io.tiko.Event;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercises every {@code @EventTrigger} feature so {@code EventTriggerTest} can drive
 * each one. Each scenario uses its own pair of event types to keep state in the
 * shared {@code received} log unambiguous between tests.
 */
@Component(scope = Scope.SINGLETON)
public class OrderTriggerService {

    private final List<Object> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<Event<?>> finalWrapper = new AtomicReference<>();
    private final AtomicReference<Thread> notifiedOnThread = new AtomicReference<>();

    // ─── Chain: OrderCreated → OrderValidated → OrderProcessed ──────────────────
    // Demonstrates return-as-payload (single trigger) and origin-chain plumbing.

    @EventHandler
    @EventTrigger(eventName = "OrderValidatedEvent")
    public OrderValidatedEvent onOrderCreated(OrderCreatedEvent event) {
        received.add(event);
        return new OrderValidatedEvent(event.id(), event.amount() >= 0, event.amount());
    }

    @EventHandler
    @EventTrigger(eventName = "OrderProcessedEvent")
    public OrderProcessedEvent onOrderValidated(OrderValidatedEvent event) {
        received.add(event);
        return new OrderProcessedEvent(event.id(), event.valid() ? "ok" : "rejected", event.amount());
    }

    @EventHandler
    public void onOrderProcessed(OrderProcessedEvent event, Event<?> wrapper) {
        received.add(event);
        finalWrapper.set(wrapper);
    }

    // ─── Multiple triggers fire ─────────────────────────────────────────────────
    // Two @EventTrigger annotations → the same return value is published twice
    // (eventName is informational under LocalEventBus; routing is by class).

    @EventHandler
    @EventTrigger(eventName = "ReplicatedEvent")
    @EventTrigger(eventName = "ReplicatedEvent")
    public ReplicatedEvent onMultiTrigger(MultiTriggerEvent event) {
        received.add(event);
        return new ReplicatedEvent(event.id());
    }

    @EventHandler
    public void onReplicated(ReplicatedEvent event) {
        received.add(event);
    }

    // ─── Guard: trigger only fires when guard returns true ──────────────────────

    @EventHandler
    @EventTrigger(eventName = "GuardedResult", guard = AmountGuard.class)
    public GuardedResult onGuardTest(GuardTestEvent event) {
        received.add(event);
        return new GuardedResult(event.id(), event.amount());
    }

    @EventHandler
    public void onGuardedResult(GuardedResult event) {
        received.add(event);
    }

    // ─── Spread: each list element becomes its own event ────────────────────────

    @EventHandler
    @EventTrigger(eventName = "BatchItemEvent", spread = true)
    public List<BatchItemEvent> onBatchReceived(BatchReceivedEvent event) {
        received.add(event);
        return event.items().stream().map(BatchItemEvent::new).toList();
    }

    @EventHandler
    public void onBatchItem(BatchItemEvent event) {
        received.add(event);
    }

    // ─── Async: trigger publishes on a different thread ─────────────────────────

    @EventHandler
    @EventTrigger(eventName = "NotifiedEvent", async = true)
    public NotifiedEvent onAudited(AuditedEvent event) {
        received.add(event);
        return new NotifiedEvent(event.id());
    }

    @EventHandler
    public void onNotified(NotifiedEvent event) {
        received.add(event);
        notifiedOnThread.set(Thread.currentThread());
    }

    // ─── Test accessors ─────────────────────────────────────────────────────────

    public List<Object> getReceived() {
        return List.copyOf(received);
    }

    public Event<?> getFinalWrapper() {
        return finalWrapper.get();
    }

    public Thread getNotifiedOnThread() {
        return notifiedOnThread.get();
    }
}
