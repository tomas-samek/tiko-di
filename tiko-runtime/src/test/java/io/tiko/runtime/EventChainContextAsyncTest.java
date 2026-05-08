package io.tiko.runtime;

import io.tiko.ErrorHandler;
import io.tiko.Event;
import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import io.tiko.Subscription;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EventChainContextAsyncTest {

    @Test
    void publishAsync_routes_handler_failures_to_error_handler() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<EventHandlerError> captured = new AtomicReference<>();
        ErrorHandler eh = ctx -> {
            if (ctx instanceof EventHandlerError e) captured.set(e);
        };
        EventBus bus = new InMemoryBus();
        bus.subscribe(String.class, e -> { throw new IllegalStateException("trigger boom"); });
        EventHandlerInfo info = new EventHandlerInfo(getClass(), "test", String.class, true);

        EventChainContext.publishAsync(bus, "hello", null, executor, eh, info).get();

        executor.shutdown();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().handler()).isEqualTo(info);
        assertThat(captured.get().event()).isEqualTo("hello");
        assertThat(captured.get().cause()).hasMessage("trigger boom");
    }

    /** Minimal in-memory bus for the test; identical semantics to LocalEventBus modulo error-handler. */
    private static final class InMemoryBus implements EventBus {
        private final Map<Class<?>, List<EventCallback<?>>> handlers = new ConcurrentHashMap<>();

        @Override
        public <T> void publish(T event) {
            List<EventCallback<?>> cs = handlers.get(event.getClass());
            if (cs == null) return;
            for (EventCallback<?> c : cs) {
                @SuppressWarnings("unchecked")
                EventCallback<T> tc = (EventCallback<T>) c;
                tc.handle(event);
            }
        }

        @Override
        public <T> Subscription subscribe(Class<T> type, EventCallback<T> cb) {
            handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(cb);
            return new Subscription() {
                @Override public void unsubscribe() {}
                @Override public boolean isActive() { return true; }
            };
        }
    }
}
