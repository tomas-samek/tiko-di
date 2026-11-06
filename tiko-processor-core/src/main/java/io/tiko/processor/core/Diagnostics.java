package io.tiko.processor.core;

import io.tiko.processor.core.model.Location;

/**
 * Compiler messages, reported on a declaration's {@link Location}. A {@code null} location
 * reports the message without a source position.
 */
public interface Diagnostics {

    void error(Location at, String message);

    void warning(Location at, String message);

    void note(Location at, String message);

    /** Whether an error has been reported through this instance. */
    boolean hasErrors();
}
