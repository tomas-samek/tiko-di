package io.tiko.runtime;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A supplied event executor as the generated dispatch code of a {@code Tiko.create(...)} boot sees
 * it (#653): every task and lifecycle call goes to the supplied executor, and a retried async
 * handler waits out its backoff on the boot's tracked retry thread ({@link RetryScheduling}).
 *
 * <p>Only the module containers hold this view. {@code Container.getEventExecutor()} on the handle
 * {@code Tiko.create(...)} returns still answers the supplied executor itself.
 */
final class RetrySchedulingExecutor extends AbstractExecutorService implements RetryScheduling {

    private final ExecutorService delegate;
    private final OwnedThreads owner;

    RetrySchedulingExecutor(ExecutorService delegate, OwnedThreads owner) {
        this.delegate = delegate;
        this.owner = owner;
    }

    /** The supplied executor behind {@code executor} when it is this view, otherwise {@code executor}. */
    static ExecutorService unwrap(ExecutorService executor) {
        return executor instanceof RetrySchedulingExecutor view ? view.delegate : executor;
    }

    @Override
    public boolean scheduleRetry(Runnable resubmit, long delayNanos) {
        return owner.scheduleRetry(resubmit, delayNanos);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
