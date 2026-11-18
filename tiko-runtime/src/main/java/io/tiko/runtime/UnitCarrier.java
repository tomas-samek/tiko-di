package io.tiko.runtime;

import io.tiko.Event;

/**
 * A container, or a view of one, that opens units of work which can be carried between threads
 * (#677). Generated containers, the container of a multi-module application, the handle
 * {@code Tiko.create(...)} returns and the {@code UnitOfWork} a component is handed all implement
 * it, and all open the application's unit.
 *
 * <p>For Tiko's own modules; applications open a unit of work with {@code runInEventScope(...)}.
 */
public interface UnitCarrier {

    /**
     * Opens a unit of work that is installed nowhere yet.
     *
     * @param origin the event the unit's work descends from, or {@code null}
     */
    CarriedUnit openCarriedUnit(Event<?> origin);

    /**
     * Whether a unit of work is open on the calling thread: an ordinary one, opened by
     * {@code runInEventScope(...)}, or a carried one that is installed there. Whoever opens a
     * unit that is not bound to one {@code Runnable} asks first, so the single-frame rule holds
     * for it as it does for {@code runInEventScope}.
     */
    boolean hasUnitOpen();
}
