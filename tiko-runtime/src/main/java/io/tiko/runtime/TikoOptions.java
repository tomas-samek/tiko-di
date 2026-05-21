package io.tiko.runtime;

import io.tiko.ConfigSource;
import io.tiko.ErrorHandler;
import java.time.Duration;
import java.util.Objects;

/**
 * Configuration knobs for {@link Tiko#create(TikoOptions)}.
 *
 * <p>Use {@link #builder()} to construct an instance. The result is immutable.
 *
 * <pre>{@code
 * TikoOptions opts = TikoOptions.builder()
 *     .configSource(ConfigSources.classpath("config.yaml"))
 *     .errorHandler(ctx -> myMetrics.recordErrorContext(ctx))
 *     .build();
 * try (Container container = Tiko.create(opts)) { ... }
 * }</pre>
 *
 * <p>All knobs are optional. When omitted, the framework supplies sensible defaults:
 * configuration binding is skipped (and fails fast at startup if any
 * {@code @Configuration} record is declared); the default error handler logs at WARN
 * via slf4j.
 */
public final class TikoOptions {

    private final ConfigSource configSource;
    private final ErrorHandler errorHandler;
    private final java.util.concurrent.ExecutorService eventExecutor;
    private final Duration shutdownTimeout;
    private final java.util.function.UnaryOperator<io.tiko.EventBus> eventBusDecorator;

    private TikoOptions(Builder b) {
        this.configSource = b.configSource;
        this.errorHandler = b.errorHandler;
        this.eventExecutor = b.eventExecutor;
        this.shutdownTimeout = b.shutdownTimeout;
        this.eventBusDecorator = b.eventBusDecorator;
    }

    /**
     * @return the configured {@link ConfigSource}, or {@code null} if none was set
     */
    public ConfigSource configSource() {
        return configSource;
    }

    /**
     * @return the configured {@link ErrorHandler}, or {@code null} to use the framework default
     */
    public ErrorHandler errorHandler() {
        return errorHandler;
    }

    /**
     * @return the user-supplied event executor, or {@code null} to use the framework default
     *         (a bounded {@link java.util.concurrent.ThreadPoolExecutor}). When user-supplied,
     *         the user owns the executor's lifecycle — {@code Container.shutdown()} does not
     *         stop it.
     */
    public java.util.concurrent.ExecutorService eventExecutor() {
        return eventExecutor;
    }

    /**
     * @return the configured graceful drain window, or {@code null} if not set. When
     *         {@code null}, the effective value is taken from the YAML key
     *         {@code tiko.shutdownTimeout} if present in the layered config sources,
     *         otherwise {@code Duration.ofSeconds(10)}.
     */
    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    /**
     * @return the configured EventBus decorator, or {@code null} when the raw {@code LocalEventBus} is used.
     *         Applied by {@link Tiko#create(TikoOptions)} after constructing the bus but before passing it
     *         to the generated container, so subscribers register against the decorated bus.
     */
    public java.util.function.UnaryOperator<io.tiko.EventBus> eventBusDecorator() {
        return eventBusDecorator;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ConfigSource configSource;
        private ErrorHandler errorHandler;
        private java.util.concurrent.ExecutorService eventExecutor;
        private Duration shutdownTimeout;
        private java.util.function.UnaryOperator<io.tiko.EventBus> eventBusDecorator;

        private Builder() {}

        public Builder configSource(ConfigSource source) {
            this.configSource = Objects.requireNonNull(source, "configSource");
            return this;
        }

        public Builder errorHandler(ErrorHandler handler) {
            this.errorHandler = Objects.requireNonNull(handler, "errorHandler");
            return this;
        }

        /**
         * Supplies a user-owned event executor in place of the framework default.
         *
         * <p>See {@link #shutdownTimeout(Duration)} for the related drain window — that knob
         * has no effect when this executor is user-supplied (you own its lifecycle).
         */
        public Builder eventExecutor(java.util.concurrent.ExecutorService executor) {
            this.eventExecutor = Objects.requireNonNull(executor, "eventExecutor");
            return this;
        }

        /**
         * Maximum time {@link io.tiko.Container#shutdown()} waits for the framework's event
         * executor to terminate gracefully before falling back to {@code shutdownNow()}.
         * When unset programmatically, the framework resolves the effective value in this
         * precedence order: programmatic > YAML {@code tiko.shutdownTimeout} > {@code Duration.ofSeconds(10)}.
         *
         * <p>Has <strong>no effect</strong> when {@link #eventExecutor(java.util.concurrent.ExecutorService)}
         * is set — the user owns the executor's lifecycle and the container does not stop it.
         *
         * <p>Note: a JVM {@link Error} (e.g. {@code OutOfMemoryError}) bypasses this graceful
         * drain. Threads may be torn down abruptly when the JVM is in an unrecoverable state.
         *
         * @param timeout non-negative duration; {@link Duration#ZERO} skips the graceful wait
         *                and calls {@code shutdownNow()} immediately
         * @throws IllegalArgumentException if {@code timeout} is negative
         * @throws NullPointerException if {@code timeout} is null
         */
        public Builder shutdownTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "shutdownTimeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("shutdownTimeout must not be negative");
            }
            this.shutdownTimeout = timeout;
            return this;
        }

        /**
         * Wraps the framework's {@link io.tiko.EventBus} before it is handed to the generated container.
         * Intended for the {@code tiko-test} {@code RecordingEventBus} spy; production code should leave this null.
         */
        public Builder eventBusDecorator(java.util.function.UnaryOperator<io.tiko.EventBus> wrap) {
            this.eventBusDecorator = Objects.requireNonNull(wrap, "eventBusDecorator");
            return this;
        }

        public TikoOptions build() {
            return new TikoOptions(this);
        }
    }
}
