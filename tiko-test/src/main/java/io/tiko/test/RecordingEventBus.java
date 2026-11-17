package io.tiko.test;

import io.tiko.Container;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
    // AtomicReference, not a volatile field: wired in after construction and read from other
    // threads, so it needs safe publication without S3077's volatile-on-a-mutable-object smell.
    private final AtomicReference<ExecutorService> eventExecutor = new AtomicReference<>();
    private final AtomicReference<Container> container = new AtomicReference<>();

    public RecordingEventBus(EventBus delegate) {
        this.delegate = delegate;
    }

    /**
     * Wires an event executor in after construction, for a recorder used without a container:
     * {@link #awaitAsyncDispatch(Duration)} then waits for that executor to drain. Under
     * {@code @TikoTest} the extension wires the container itself ({@link #setContainer}).
     */
    public void setEventExecutor(ExecutorService executor) {
        this.eventExecutor.set(executor);
    }

    /**
     * Wires the booted container in after construction. Called by the JUnit extension once
     * {@code Tiko.create(...)} returns. {@link #awaitAsyncDispatch(Duration)} then waits for what
     * {@link Container#pendingAsyncDeliveries()} reports, which covers a retry waiting out its
     * backoff as well as what is queued or running (#761).
     */
    public void setContainer(Container container) {
        this.container.set(container);
        this.eventExecutor.set(container.getEventExecutor());
    }

    @Override
    public <T> void publish(T event) {
        // A null-returning bridge publishes nothing (#757): there is no event to record.
        if (event != null) {
            captured.add(event);
        }
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
     * Blocks until no async handler is queued, running, or waiting out the backoff before a retry,
     * or the timeout elapses. Useful for tests that exercise {@code @EventHandler(async = true)}
     * paths. Without a wired container it waits for the wired executor to drain instead (active
     * count zero, empty queue), which does not see a retry in its backoff.
     *
     * @throws IllegalStateException if neither {@link #setContainer(Container)} nor
     *     {@link #setEventExecutor(ExecutorService)} has been called
     * @throws TimeoutException if the work does not settle within {@code timeout}
     */
    public void awaitAsyncDispatch(Duration timeout) throws TimeoutException {
        var exec = this.eventExecutor.get();
        if (exec == null) {
            throw new IllegalStateException(
                    "awaitAsyncDispatch requires the event executor to be wired (setEventExecutor). "
                            + "Under @TikoTest this happens automatically; outside @TikoTest call setEventExecutor first.");
        }
        var booted = this.container.get();
        if (exec instanceof CountingThreadPoolExecutor cte) {
            // Deterministic: in-flight is counted at submit time, so a task is never uncounted
            // while it sits between the queue and a worker lock — the window that flaked #443.
            // The container adds the retries that wait out a backoff, which no executor holds (#761).
            pollUntilDrained(
                    () -> cte.inFlight() > 0 || pendingDeliveries(booted) > 0,
                    () -> "inFlight=" + cte.inFlight() + ", pendingDeliveries=" + pendingDeliveries(booted),
                    timeout);
            return;
        }
        if (booted != null) {
            pollUntilDrained(
                    () -> booted.pendingAsyncDeliveries() > 0,
                    () -> "pendingDeliveries=" + booted.pendingAsyncDeliveries(),
                    timeout);
            return;
        }
        if (exec instanceof ThreadPoolExecutor tpe) {
            // Best-effort for a directly-wired ThreadPoolExecutor: getActiveCount() and the queue are
            // approximate, unsynchronized samples (see CountingThreadPoolExecutor for why that races).
            pollUntilDrained(
                    () -> tpe.getActiveCount() > 0 || !tpe.getQueue().isEmpty(),
                    () -> "activeCount=" + tpe.getActiveCount() + ", queue="
                            + tpe.getQueue().size(),
                    timeout);
            return;
        }
        // Custom executors can't be polled — fall back to a fixed sleep capped at 1s.
        try {
            Thread.sleep(Math.min(timeout.toMillis(), 1_000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Polls {@code inFlight} every 10ms until it reports drained or {@code timeout} elapses.
     *
     * @param inFlight true while work remains outstanding
     * @param detail lazily renders the outstanding-work snapshot for the timeout message
     * @throws TimeoutException if work is still outstanding when {@code timeout} elapses
     */
    private static void pollUntilDrained(BooleanSupplier inFlight, Supplier<String> detail, Duration timeout)
            throws TimeoutException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new TimeoutException(
                        "Async event dispatch did not drain within " + timeout + " (" + detail.get() + ")");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static int pendingDeliveries(Container booted) {
        return booted == null ? 0 : booted.pendingAsyncDeliveries();
    }

    private String summary() {
        return captured.stream().map(e -> e.getClass().getSimpleName()).toList().toString();
    }
}
