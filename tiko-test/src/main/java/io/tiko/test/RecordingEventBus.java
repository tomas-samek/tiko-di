package io.tiko.test;

import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.Subscription;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;

/**
 * Spy {@link EventBus} that records every published event before forwarding to a delegate bus.
 *
 * <p>Intended for use with the {@code tiko-test} JUnit extension: tests can publish events through
 * the container in the usual way and then assert on what flowed through the bus via
 * {@link #assertPublished(Class)}, {@link #assertPublishedExactly(int, Class)}, and
 * {@link #assertNoneOf(Class)}.
 *
 * <p>The recorder is a transparent decorator — every {@code publish} call is captured locally and
 * then forwarded to the wrapped {@link EventBus}, so production handlers still see every event.
 * Subscriptions are likewise passed straight through to the delegate.
 *
 * <p>Captured events live in a {@link CopyOnWriteArrayList} so assertions made from a different
 * thread than the publisher (e.g. after {@link #awaitAsyncDispatch(Duration)}) see a consistent
 * snapshot.
 */
public final class RecordingEventBus implements EventBus {

    private final EventBus delegate;
    private final List<Object> captured = new CopyOnWriteArrayList<>();
    private volatile ExecutorService eventExecutor;

    public RecordingEventBus(EventBus delegate) {
        this.delegate = delegate;
    }

    /**
     * Wires the container's event executor in after construction. Called by the JUnit
     * extension once {@code Tiko.create(...)} returns. Required for
     * {@link #awaitAsyncDispatch(Duration)} to function.
     */
    public void setEventExecutor(ExecutorService executor) {
        this.eventExecutor = executor;
    }

    @Override
    public <T> void publish(T event) {
        captured.add(event);
        delegate.publish(event);
    }

    @Override
    public <T> Subscription subscribe(Class<T> type, EventCallback<T> cb) {
        return delegate.subscribe(type, cb);
    }

    /**
     * Asserts that at least one event of {@code type} (or a subtype) was published.
     *
     * @return a {@link PublishedEventAssertion} for chaining further checks
     * @throws AssertionError if no matching event was captured
     */
    public PublishedEventAssertion assertPublished(Class<?> type) {
        var matches = events(type);
        if (matches.isEmpty()) {
            throw new AssertionError(
                    "Expected to find published " + type.getSimpleName() + " but captured: " + summary());
        }
        return new PublishedEventAssertion(type, new ArrayList<>(matches));
    }

    /** Asserts the captured count of {@code type} (or subtypes) equals {@code n}. */
    public void assertPublishedExactly(int n, Class<?> type) {
        var count = events(type).size();
        if (count != n) {
            throw new AssertionError("Expected exactly " + n + " " + type.getSimpleName() + ", saw " + count
                    + ". Captured: " + summary());
        }
    }

    /** Asserts no event of {@code type} (or subtypes) was published. */
    public void assertNoneOf(Class<?> type) {
        var matches = events(type);
        if (!matches.isEmpty()) {
            throw new AssertionError(
                    "Expected zero " + type.getSimpleName() + ", saw " + matches.size() + ": " + matches);
        }
    }

    /** Returns a defensive copy of every captured event, in publish order. */
    public List<Object> events() {
        return new ArrayList<>(captured);
    }

    /** Returns a defensive copy of captured events assignable to {@code type}, in publish order. */
    @SuppressWarnings("unchecked")
    public <T> List<T> events(Class<T> type) {
        var out = new ArrayList<T>();
        for (Object e : captured) {
            if (type.isInstance(e)) {
                out.add((T) e);
            }
        }
        return out;
    }

    /** Discards every captured event. The delegate bus and any wired executor are left alone. */
    public void clear() {
        captured.clear();
    }

    /**
     * Blocks until the wired event executor has drained (active count zero, empty queue), or the
     * timeout elapses. Useful for tests that exercise {@code @EventHandler(async = true)} paths.
     *
     * @throws IllegalStateException if {@link #setEventExecutor(ExecutorService)} has not been called
     * @throws TimeoutException if the executor does not drain within {@code timeout}
     */
    public void awaitAsyncDispatch(Duration timeout) throws TimeoutException {
        var exec = this.eventExecutor;
        if (exec == null) {
            throw new IllegalStateException(
                    "awaitAsyncDispatch requires the event executor to be wired (setEventExecutor). "
                            + "Under @TikoTest this happens automatically; outside @TikoTest call setEventExecutor first.");
        }
        if (!(exec instanceof ThreadPoolExecutor tpe)) {
            // Custom executors can't be polled — fall back to a fixed sleep capped at 1s.
            try {
                Thread.sleep(Math.min(timeout.toMillis(), 1_000L));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        var deadline = System.nanoTime() + timeout.toNanos();
        while (tpe.getActiveCount() > 0 || !tpe.getQueue().isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new TimeoutException("Async event dispatch did not drain within " + timeout + " (activeCount="
                        + tpe.getActiveCount() + ", queue="
                        + tpe.getQueue().size() + ")");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private String summary() {
        return captured.stream().map(e -> e.getClass().getSimpleName()).toList().toString();
    }
}
