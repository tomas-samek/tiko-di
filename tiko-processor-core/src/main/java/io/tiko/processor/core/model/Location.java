package io.tiko.processor.core.model;

import java.util.Optional;

/**
 * An opaque source position. {@link io.tiko.processor.core.Diagnostics} reads it to place a
 * message, and error bookkeeping reads {@link #enclosingClassName()}; nothing else inspects it.
 * Each frontend adapter wraps its own element type behind it.
 *
 * <p>A location stays valid in later rounds: reported on a later round's
 * {@link io.tiko.processor.core.Diagnostics}, a message lands on the same declaration.
 */
public interface Location {

    /**
     * The qualified name of the nearest class enclosing this position: the class itself for a
     * class's location, the declaring class for a member, parameter or annotation on either.
     * Empty when no class encloses the position.
     */
    Optional<String> enclosingClassName();
}
