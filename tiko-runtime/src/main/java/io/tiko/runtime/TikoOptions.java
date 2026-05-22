package io.tiko.runtime;

import io.tiko.ConfigSource;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
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
    private final java.util.function.UnaryOperator<EventBus> eventBusDecorator;
    private final java.util.Map<OverrideKey, java.util.function.Supplier<?>> overrides;

    private TikoOptions(Builder b) {
        this.configSource = b.configSource;
        this.errorHandler = b.errorHandler;
        this.eventExecutor = b.eventExecutor;
        this.shutdownTimeout = b.shutdownTimeout;
        this.eventBusDecorator = b.eventBusDecorator;
        this.overrides = b.overrides == null
                ? new java.util.concurrent.ConcurrentHashMap<>()
                : new java.util.concurrent.ConcurrentHashMap<>(b.overrides);
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
    public java.util.function.UnaryOperator<EventBus> eventBusDecorator() {
        return eventBusDecorator;
    }

    public boolean hasOverride(Class<?> type) {
        return overrides.containsKey(new OverrideKey(type, ""));
    }

    public boolean hasOverride(Class<?> type, String name) {
        Objects.requireNonNull(name, "name");
        return overrides.containsKey(new OverrideKey(type, name));
    }

    public java.util.function.Supplier<?> getOverride(Class<?> type) {
        return overrides.get(new OverrideKey(type, ""));
    }

    public java.util.function.Supplier<?> getOverride(Class<?> type, String name) {
        Objects.requireNonNull(name, "name");
        return overrides.get(new OverrideKey(type, name));
    }

    /**
     * Package-private entry point used by {@link AggregatingContainer} to register
     * shadow-declared overrides AFTER {@link Builder#build()} has been called.
     * User code cannot reach this method; it is the only mutation surface on
     * {@link TikoOptions} outside the {@link Builder}. Always overwrites.
     *
     * @see #internalAddOverrideIfAbsent(Class, java.util.function.Supplier)
     */
    <T> void internalAddOverride(Class<T> type, java.util.function.Supplier<? extends T> supplier) {
        overrides.put(new OverrideKey(type, ""), supplier);
    }

    /**
     * Same as {@link #internalAddOverride(Class, java.util.function.Supplier)} but
     * a no-op when the key already has an override. Used to give user-provided
     * overrides precedence over shadow-declared ones.
     */
    <T> void internalAddOverrideIfAbsent(Class<T> type, java.util.function.Supplier<? extends T> supplier) {
        overrides.putIfAbsent(new OverrideKey(type, ""), supplier);
    }

    /**
     * Named-key variant of {@link #internalAddOverrideIfAbsent(Class, java.util.function.Supplier)}.
     */
    <T> void internalAddOverrideIfAbsent(
            Class<T> type, String name, java.util.function.Supplier<? extends T> supplier) {
        Objects.requireNonNull(name, "name");
        overrides.putIfAbsent(new OverrideKey(type, name), supplier);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ConfigSource configSource;
        private ErrorHandler errorHandler;
        private java.util.concurrent.ExecutorService eventExecutor;
        private Duration shutdownTimeout;
        private java.util.function.UnaryOperator<EventBus> eventBusDecorator;
        private java.util.Map<OverrideKey, java.util.function.Supplier<?>> overrides;

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
         * Typical uses are observability spies and test-time recording wrappers; production code should leave this null.
         */
        public Builder eventBusDecorator(java.util.function.UnaryOperator<EventBus> wrap) {
            this.eventBusDecorator = Objects.requireNonNull(wrap, "eventBusDecorator");
            return this;
        }

        public <T> Builder override(Class<T> type, java.util.function.Supplier<? extends T> supplier) {
            return overrideKey(new OverrideKey(type, ""), supplier);
        }

        public <T> Builder override(Class<T> type, String name, java.util.function.Supplier<? extends T> supplier) {
            Objects.requireNonNull(name, "name");
            return overrideKey(new OverrideKey(type, name), supplier);
        }

        private Builder overrideKey(OverrideKey key, java.util.function.Supplier<?> supplier) {
            Objects.requireNonNull(supplier, "supplier");
            if (overrides == null) overrides = new java.util.LinkedHashMap<>();
            overrides.put(key, supplier);
            return this;
        }

        public TikoOptions build() {
            return new TikoOptions(this);
        }
    }

    /** Internal key used to address overrides by type + optional qualifier. */
    record OverrideKey(Class<?> type, String name) {
        OverrideKey {
            Objects.requireNonNull(type, "type");
        }
    }
}
