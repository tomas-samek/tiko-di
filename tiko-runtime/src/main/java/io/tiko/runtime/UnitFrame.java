package io.tiko.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One container's EVENT frame as a value (#677): what a generated container otherwise keeps in
 * its thread-locals. A {@link CarriedUnit} holds one for each module and swaps it onto the thread
 * its work runs on ({@link DetachedUnitFrame#swapFrame}).
 *
 * <p>For generated containers and the runtime; applications don't use it.
 *
 * @param scope the frame's EVENT-scoped instances by component key, in creation order
 * @param open whether a unit of work is open in the frame
 */
public record UnitFrame(Map<String, Object> scope, boolean open) {

    /** The frame of a unit of work that has just been opened: it holds no instance yet. */
    public static UnitFrame opened() {
        return new UnitFrame(new LinkedHashMap<>(), true);
    }

    /** A frame with no unit of work open in it: what a detached unit sets the caller's frame aside for. */
    public static UnitFrame closed() {
        return new UnitFrame(new LinkedHashMap<>(), false);
    }
}
