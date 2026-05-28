package io.tiko;

/**
 * Thrown when the container cannot be brought up: no generated container on the classpath, a
 * malformed module descriptor, a config record no module owns, an invalid framework config value
 * ({@code tiko.shutdownTimeout}), or a reflective bootstrap failure. Always a startup-time failure —
 * the container never becomes usable.
 *
 * <p>The {@code cause} is preserved where the failure wraps an underlying throwable (a
 * {@code ClassNotFoundException}, a coercion error, etc.).
 */
public final class ContainerInitializationException extends TikoException {

    public ContainerInitializationException(String message) {
        super(message);
    }

    public ContainerInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
