package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ErrorContext;
import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.EventQueueOverflowException;
import io.tiko.Subscription;
import io.tiko.runtime.OverflowPolicy;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #714: with {@link OverflowPolicy#THROW}, {@code publish} throws {@link EventQueueOverflowException}
 * to the publisher when the async queue is full, through the real bus and through a decorator. The
 * events that were accepted are still handled, and nothing is routed to the {@code ErrorHandler}.
 */
class OverflowThrowTest {

    private final List<ErrorContext> routed = new CopyOnWriteArrayList<>();
    private final CountDownLatch gate = new CountDownLatch(1);

    @BeforeEach
    void closeTheGate() {
        GatedAsyncRecorder.handled.clear();
        GatedAsyncRecorder.entered = new CountDownLatch(1);
        GatedAsyncRecorder.done = new CountDownLatch(2);
        GatedAsyncRecorder.gate = gate;
    }

    @AfterEach
    void openTheGate() {
        gate.countDown();
        GatedAsyncRecorder.gate = null;
    }

    static Stream<Arguments> buses() {
        UnaryOperator<EventBus> forwarding = ForwardingBus::new;
        return Stream.of(Arguments.of("plain bus", null), Arguments.of("decorated bus", forwarding));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("buses")
    void publishThrowsToThePublisherWhenTheQueueIsFull(String name, UnaryOperator<EventBus> decorator)
            throws InterruptedException {
        var options = TikoOptions.builder()
                .eventExecutorCoreSize(1)
                .eventExecutorMaxSize(1)
                .queueCapacity(1)
                .onOverflow(OverflowPolicy.THROW)
                .errorHandler(routed::add);
        if (decorator != null) {
            options.eventBusDecorator(decorator);
        }

        try (var container = Tiko.create(options.build())) {
            var bus = container.getEventBus();
            bus.publish(new GatedPing(1));
            assertThat(GatedAsyncRecorder.entered.await(10, TimeUnit.SECONDS))
                    .as("the first handler holds the only worker")
                    .isTrue();
            bus.publish(new GatedPing(2));

            assertThatThrownBy(() -> bus.publish(new GatedPing(3))).isInstanceOf(EventQueueOverflowException.class);

            gate.countDown();
            assertThat(GatedAsyncRecorder.done.await(10, TimeUnit.SECONDS))
                    .as("the two accepted events are handled")
                    .isTrue();
            assertThat(GatedAsyncRecorder.handled).containsExactly(1, 2);
        }
        assertThat(routed).isEmpty();
    }

    /** A decorator that only forwards, as a tracing or recording bus does. */
    private record ForwardingBus(EventBus delegate) implements EventBus {
        @Override
        public <T> void publish(T event) {
            delegate.publish(event);
        }

        @Override
        public <T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback) {
            return delegate.subscribe(eventType, callback);
        }
    }
}
