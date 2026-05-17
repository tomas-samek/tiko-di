package io.tiko.runtime;

import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.Subscription;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple in-memory event bus implementation.
 * <p>
 * Thread-safe and synchronous. Per-callback exceptions are isolated: a throw from one
 * subscriber does not prevent subsequent subscribers from running, and does not propagate
 * to the publisher.
 *
 * <p>Subscribers registered via {@code @EventHandler} (i.e. through the generated
 * {@code EventRegistry}) have their exceptions reported through the configured
 * {@code ErrorHandler} with a rich {@code EventHandlerError}. Subscribers registered
 * programmatically (via {@link #subscribe(Class, EventCallback)} from user code) are
 * not associated with any compile-time identity, so the bus logs their exceptions at
 * WARNING via {@link java.lang.System.Logger} as a defense-in-depth net.
 */
public final class LocalEventBus implements EventBus {

    // Lazy holder: defers System.LoggerFinder resolution until the first
    // programmatic-callback exception fires. Most apps never hit this path during
    // startup, so the bus's <clinit> stays free of logging-init cost.
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger(LocalEventBus.class.getName());
    }

    private final Map<Class<?>, List<EventCallback<?>>> handlers = new ConcurrentHashMap<>();

    @Override
    public <T> void publish(T event) {
        if (event == null) {
            return;
        }

        Class<?> eventType = event.getClass();
        List<EventCallback<?>> callbacks = handlers.get(eventType);

        if (callbacks == null) {
            return;
        }

        for (EventCallback<?> callback : callbacks) {
            @SuppressWarnings("unchecked")
            EventCallback<T> typedCallback = (EventCallback<T>) callback;
            try {
                typedCallback.handle(event);
            } catch (Exception e) {
                // Defense-in-depth: the generated dispatcher already catches and reports
                // its own throws via the ErrorHandler with a rich EventHandlerInfo. This
                // branch fires only for programmatic EventCallback subscribers (no
                // @EventHandler, no compile-time identity). Log at WARNING — Errors (OOM,
                // StackOverflow) are deliberately not caught here; those mean the JVM
                // is sick and surfacing them is the right move.
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        e,
                        "Programmatic event callback threw on event {0}: {1}",
                        eventType.getName(),
                        e);
            }
        }
    }

    @Override
    public <T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback) {
        List<EventCallback<?>> callbacks = handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
        callbacks.add(callback);

        return new LocalSubscription<>(callbacks, callback);
    }

    private static final class LocalSubscription<T> implements Subscription {
        private final List<EventCallback<?>> callbacks;
        private final EventCallback<T> callback;
        private final AtomicBoolean active = new AtomicBoolean(true);

        LocalSubscription(List<EventCallback<?>> callbacks, EventCallback<T> callback) {
            this.callbacks = callbacks;
            this.callback = callback;
        }

        @Override
        public void unsubscribe() {
            if (active.compareAndSet(true, false)) {
                callbacks.remove(callback);
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }
    }
}
