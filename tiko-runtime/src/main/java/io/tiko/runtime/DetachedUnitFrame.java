package io.tiko.runtime;

/**
 * A module's container as one part of a unit of work that is opened off the calling thread's own
 * frame. There are two such units: the detached unit an async event handler runs in (#220), and a
 * {@link CarriedUnit}, which is put on a thread and taken off again (#677). In a multi-module
 * application either one is open in every module (#811), so the application asks each module's
 * container for its frame.
 *
 * <p>Generated containers implement this; applications don't call it. A unit of work is opened
 * with {@code container.runInEventScope(...)}.
 */
public interface DetachedUnitFrame {

    /**
     * Runs {@code task} in a fresh EVENT frame of this container. The frame the calling thread has
     * open here, if any, is set aside for the duration and restored afterwards; the EVENT-scoped
     * components created in the fresh frame are torn down when {@code task} returns.
     *
     * @param publishLifecycle whether this container publishes the unit's
     *     {@code EventStartedEvent} / {@code EventEndingEvent} pair; {@code false} when the caller
     *     publishes it for the whole application
     */
    void runInDetachedFrame(Runnable task, boolean publishLifecycle);

    /**
     * Makes {@code frame} this container's EVENT frame on the calling thread.
     *
     * @return the frame it replaced, for the caller to swap back in
     */
    UnitFrame swapFrame(UnitFrame frame);

    /**
     * Tears down the frame that is this container's on the calling thread: the destroy hooks of
     * its EVENT-scoped components run in reverse creation order, and the frame ends empty with no
     * unit open in it.
     */
    void closeFrame();
}
