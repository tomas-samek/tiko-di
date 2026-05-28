package io.tiko;

/**
 * Thrown when a component is requested from a container that has already been shut down. The
 * container's lookup methods reject access once {@code shutdown()} has completed, rather than
 * handing back a torn-down singleton.
 */
public final class ContainerShutDownException extends TikoException {

    public ContainerShutDownException() {
        super("Container has been shut down");
    }
}
