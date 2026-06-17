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
}
