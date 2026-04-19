package io.tiko.event.local;

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
 * Thread-safe and synchronous.
 */
public final class LocalEventBus implements EventBus {

    private final Map<Class<?>, List<EventCallback<?>>> handlers = new ConcurrentHashMap<>();

    @Override
    public <T> void publish(T event) {
        if (event == null) {
            return;
        }

        Class<?> eventType = event.getClass();
        List<EventCallback<?>> callbacks = handlers.get(eventType);

        if (callbacks != null) {
            for (EventCallback<?> callback : callbacks) {
                @SuppressWarnings("unchecked")
                EventCallback<T> typedCallback = (EventCallback<T>) callback;
                typedCallback.handle(event);
            }
        }
    }

    @Override
    public <T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback) {
        List<EventCallback<?>> callbacks = handlers.computeIfAbsent(
            eventType,
            k -> new CopyOnWriteArrayList<>()
        );
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
