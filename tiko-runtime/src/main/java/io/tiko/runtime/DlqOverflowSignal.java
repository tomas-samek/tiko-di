package io.tiko.runtime;

import java.util.concurrent.RejectedExecutionException;

/**
 * Internal marker thrown by {@link OverflowRejectionHandler} when the overflow policy is
 * {@link OverflowPolicy#ROUTE_TO_DLQ} (#111). It propagates synchronously out of the async
 * submit (a rejected {@code executor.execute(...)}) to the dispatch site in
 * {@link EventChainContext}, which has the event payload and {@code ErrorHandler} in scope and
 * routes an {@link io.tiko.EventDispatchRejected}.
 *
 * <p>Distinct from {@link io.tiko.EventQueueOverflowException} (the {@code THROW} policy) so the
 * dispatch site can tell "route to DLQ and swallow" from "throw back to the publisher". Package-
 * private and never surfaced to user code.
 */
final class DlqOverflowSignal extends RejectedExecutionException {

    DlqOverflowSignal(String message) {
        super(message);
    }

    /**
     * Suppresses stack-trace capture. This is a control-flow marker thrown and immediately caught at
     * the dispatch site — its trace is never read — and it is thrown on the overload path (a full
     * queue), exactly where the wasted native stack walk would hurt most.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
