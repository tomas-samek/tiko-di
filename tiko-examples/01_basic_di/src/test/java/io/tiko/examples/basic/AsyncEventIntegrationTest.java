package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.Tiko;
import io.tiko.TikoOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncEventIntegrationTest {

    @BeforeEach
    void resetRecorder() {
        AsyncRecorder.threadName.set(null);
        AsyncRecorder.latch = new CountDownLatch(1);
    }

    @Test
    void async_handler_runs_off_publisher_thread() throws Exception {
        try (Container container = Tiko.create()) {
            String publisherThread = Thread.currentThread().getName();
            container.getEventBus().publish(new AsyncPing());

            assertThat(AsyncRecorder.latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(AsyncRecorder.threadName.get())
                .isNotEqualTo(publisherThread)
                .startsWith("tiko-event-async-");
        }
    }

    @Test
    void async_handler_error_routes_to_error_handler_even_when_future_discarded() throws Exception {
        AtomicReference<ErrorContext> captured = new AtomicReference<>();
        CountDownLatch errorLatch = new CountDownLatch(1);
        ErrorHandler recording = ctx -> {
            captured.set(ctx);
            errorLatch.countDown();
        };

        TikoOptions opts = TikoOptions.builder().errorHandler(recording).build();
        try (Container container = Tiko.create(opts)) {
            container.getEventBus().publish(new AsyncPing());

            assertThat(errorLatch.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(captured.get()).isInstanceOf(EventHandlerError.class);
        EventHandlerError err = (EventHandlerError) captured.get();
        assertThat(err.handler().async()).isTrue();
        assertThat(err.cause()).hasMessage("async boom");
    }

    @Test
    void custom_event_executor_is_used() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        ExecutorService delegate = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "custom-executor-thread");
            t.setDaemon(true);
            return t;
        });
        ExecutorService recording = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { delegate.shutdown(); }
            @Override public java.util.List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
            @Override public boolean isShutdown() { return delegate.isShutdown(); }
            @Override public boolean isTerminated() { return delegate.isTerminated(); }
            @Override public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException {
                return delegate.awaitTermination(t, u);
            }
            @Override public void execute(Runnable command) {
                submissions.incrementAndGet();
                delegate.execute(command);
            }
        };

        // Suppress the AsyncThrower from polluting stderr with stack traces — using a no-op handler.
        ErrorHandler silent = ctx -> {};
        TikoOptions opts = TikoOptions.builder().eventExecutor(recording).errorHandler(silent).build();
        try (Container container = Tiko.create(opts)) {
            assertThat(container.getEventExecutor()).isSameAs(recording);
            container.getEventBus().publish(new AsyncPing());

            assertThat(AsyncRecorder.latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(submissions.get()).isPositive();
        } finally {
            recording.shutdown();
        }
    }

    @Test
    void user_supplied_executor_is_not_shut_down_by_container() {
        ExecutorService user = Executors.newSingleThreadExecutor();
        // Silent error handler so the AsyncThrower's exception doesn't pollute test output.
        ErrorHandler silent = ctx -> {};
        TikoOptions opts = TikoOptions.builder().eventExecutor(user).errorHandler(silent).build();
        try (Container container = Tiko.create(opts)) {
            // empty body — close at end
        }
        assertThat(user.isShutdown()).isFalse();
        user.shutdown();
    }
}
