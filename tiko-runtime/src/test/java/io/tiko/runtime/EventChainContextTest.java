package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.Event;
import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import io.tiko.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link EventChainContext} — the thread-local origin tracking + trigger publishing used
 * by generated {@code EventRegistry} code: {@code wrap}/{@code enter}/{@code exit}, the sync and
 * spread publish helpers across the Collection/array/Iterable/scalar branches, and the async helpers
 * (including failure routing to the {@link ErrorHandler}).
 */
class EventChainContextTest {

    private static final EventHandlerInfo INFO =
            new EventHandlerInfo(EventChainContextTest.class, "h", String.class, true);

    @AfterEach
    void clearThreadLocal() {
        EventChainContext.exit(null); // tests share a thread; never leak the current wrapper
    }

    /** Records each published payload and the chain origin in effect at publish time. */
    private static final class RecordingBus implements EventBus {
        final List<Object> published = new ArrayList<>();
        final List<Event<?>> originAtPublish = new ArrayList<>();

        @Override
        public <T> void publish(T event) {
            published.add(event);
            originAtPublish.add(EventChainContext.wrap(event).getOrigin().orElse(null));
        }

        @Override
        public <T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback) {
            return new Subscription() {
                @Override
                public void unsubscribe() {}

                @Override
                public boolean isActive() {
                    return false;
                }
            };
        }
    }

    @Test
    void wrapAtRootHasNoOrigin() {
        Event<String> e = EventChainContext.wrap("x");
        assertThat(e.getPayload()).isEqualTo("x");
        assertThat(e.getOrigin()).isEmpty();
    }

    @Test
    void runWithSetsCurrentDuringBodyAndRestoresAfter() {
        Event<String> wrapper = new Event<>("root");
        assertThat(EventChainContext.wrap("before").getOrigin()).isEmpty();

        EventChainContext.runWith(
                wrapper,
                () -> assertThat(EventChainContext.wrap("inner").getOrigin()).contains(wrapper));

        assertThat(EventChainContext.wrap("after").getOrigin()).isEmpty();
    }

    @Test
    void enterReturnsPreviousAndExitRestoresIt() {
        Event<String> w1 = new Event<>("1");
        Event<String> w2 = new Event<>("2");

        assertThat(EventChainContext.enter(w1)).isNull();
        assertThat(EventChainContext.wrap("x").getOrigin()).contains(w1);

        Event<?> prev = EventChainContext.enter(w2);
        assertThat(prev).isEqualTo(w1);
        assertThat(EventChainContext.wrap("y").getOrigin()).contains(w2);

        EventChainContext.exit(prev); // back to w1
        assertThat(EventChainContext.wrap("z").getOrigin()).contains(w1);
    }

    @Test
    void publishWithOriginPublishesNonNullUnderOrigin() {
        RecordingBus bus = new RecordingBus();
        Event<String> origin = new Event<>("root");

        EventChainContext.publishWithOrigin(bus, "payload", origin);
        EventChainContext.publishWithOrigin(bus, null, origin); // null is skipped

        assertThat(bus.published).containsExactly("payload");
        assertThat(bus.originAtPublish).containsExactly(origin);
    }

    @Test
    void publishSpreadEmitsEachElementSkippingNulls() {
        RecordingBus bus = new RecordingBus();
        Event<String> origin = new Event<>("root");

        // Collection (with a null), array, Iterable, and a scalar fallback.
        EventChainContext.publishSpreadWithOrigin(bus, java.util.Arrays.asList("a", null, "b"), origin);
        EventChainContext.publishSpreadWithOrigin(bus, new String[] {"c"}, origin);
        EventChainContext.publishSpreadWithOrigin(bus, (Iterable<String>) List.of("d"), origin);
        EventChainContext.publishSpreadWithOrigin(bus, "scalar", origin);
        EventChainContext.publishSpreadWithOrigin(bus, null, origin); // skipped

        assertThat(bus.published).containsExactly("a", "b", "c", "d", "scalar");
        assertThat(bus.originAtPublish).allSatisfy(o -> assertThat(o).isEqualTo(origin));
    }

    @Test
    void publishAsyncCompletesAndPublishesOnSuccess() throws Exception {
        RecordingBus bus = new RecordingBus();
        List<ErrorContext> errors = new ArrayList<>();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            EventChainContext.publishAsync(bus, "p", new Event<>("root"), exec, errors::add, INFO)
                    .get(2, TimeUnit.SECONDS);
            assertThat(bus.published).containsExactly("p");
            assertThat(errors).isEmpty();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void publishAsyncRoutesPublishFailureToErrorHandler() throws Exception {
        IllegalStateException boom = new IllegalStateException("boom");
        EventBus throwingBus = new EventBus() {
            @Override
            public <T> void publish(T event) {
                throw boom;
            }

            @Override
            public <T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback) {
                throw new UnsupportedOperationException();
            }
        };
        List<ErrorContext> errors = new ArrayList<>();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            EventChainContext.publishAsync(throwingBus, "p", new Event<>("root"), exec, errors::add, INFO)
                    .get(2, TimeUnit.SECONDS);

            assertThat(errors).singleElement().isInstanceOfSatisfying(EventHandlerError.class, e -> {
                assertThat(e.cause()).isSameAs(boom);
                assertThat(e.handler()).isEqualTo(INFO);
            });
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void publishAsyncSwallowsErrorHandlerFailureAsLastResort() throws Exception {
        EventBus throwingBus = new EventBus() {
            @Override
            public <T> void publish(T event) {
                throw new IllegalStateException("boom");
            }

            @Override
            public <T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback) {
                throw new UnsupportedOperationException();
            }
        };
        ErrorHandler throwingHandler = ctx -> {
            throw new RuntimeException("handler also throws");
        };
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            // The handler throwing must not propagate out of the future (logErrorHandlerFailure path).
            assertThatCode(() -> EventChainContext.publishAsync(
                                    throwingBus, "p", new Event<>("root"), exec, throwingHandler, INFO)
                            .get(2, TimeUnit.SECONDS))
                    .doesNotThrowAnyException();
        } finally {
            exec.shutdownNow();
        }
    }
}
