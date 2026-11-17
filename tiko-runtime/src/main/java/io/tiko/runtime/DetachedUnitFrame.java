package io.tiko.runtime;

/**
 * A module's container as one part of a detached unit of work: the unit an async event handler
 * runs in (#220). In a multi-module application that unit is open in every module (#811), so the
 * application asks each module's container for a frame of its own.
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
}
