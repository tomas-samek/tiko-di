package io.tiko.runtime;

import io.tiko.ConfigSource;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.TransportBootstrap;
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
    private final Duration teardownTimeout;
    private final java.util.function.UnaryOperator<EventBus> eventBusDecorator;
    private final int queueCapacity;
    private final OverflowPolicy overflowPolicy;
    private final int eventExecutorCoreSize;
    private final int eventExecutorMaxSize;
    private final Duration eventExecutorKeepAlive;
    private final java.util.Map<OverrideKey, java.util.function.Supplier<?>> overrides;
    private final java.util.Map<Class<?>, java.util.function.Function<TransportBootstrap, TransportBootstrap>>
            transportReplacements;

    /** Sentinel for an unset pool-size knob: the framework derives the default from {@code availableProcessors()}. */
    static final int UNSET_POOL_SIZE = -1;

    private TikoOptions(Builder b) {
        this.configSource = b.configSource;
        this.errorHandler = b.errorHandler;
        this.eventExecutor = b.eventExecutor;
        this.shutdownTimeout = b.shutdownTimeout;
        this.teardownTimeout = b.teardownTimeout;
        this.eventBusDecorator = b.eventBusDecorator;
        this.queueCapacity = b.queueCapacity;
        this.overflowPolicy = b.overflowPolicy;
        this.eventExecutorCoreSize = b.eventExecutorCoreSize;
        this.eventExecutorMaxSize = b.eventExecutorMaxSize;
        this.eventExecutorKeepAlive = b.eventExecutorKeepAlive;
        this.overrides = b.overrides == null
                ? new java.util.concurrent.ConcurrentHashMap<>()
                : new java.util.concurrent.ConcurrentHashMap<>(b.overrides);
        this.transportReplacements = b.transportReplacements == null
                ? java.util.Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(b.transportReplacements));
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
     * @return the per-component teardown bound, or {@code null} when unset (the default —
     *         each SINGLETON {@code @PreDestroy} / factory {@code AutoCloseable.close()} runs
     *         inline on the shutdown thread with no time limit, exactly as before). When set,
     *         {@code Container.shutdown()} runs each such hook under this bound and routes a
     *         {@code TimeoutException}-caused {@code PreDestroyFailure} / {@code AutoCloseFailure}
     *         through the {@link ErrorHandler} if the hook overruns, then continues to the next
     *         component. REQUEST/EVENT scope-exit teardown is unaffected.
     */
    public Duration teardownTimeout() {
        return teardownTimeout;
    }

    /**
     * @return the configured EventBus decorator, or {@code null} when the raw {@code LocalEventBus} is used.
     *         Applied by {@link Tiko#create(TikoOptions)} after constructing the bus but before passing it
     *         to the generated container, so subscribers register against the decorated bus.
     */
    public java.util.function.UnaryOperator<EventBus> eventBusDecorator() {
        return eventBusDecorator;
    }

    /**
     * @return the bounded work-queue capacity for the framework default event executor (#109).
     *         Defaults to {@code 1024}. Ignored when a custom {@link #eventExecutor()} is supplied.
     */
    public int queueCapacity() {
        return queueCapacity;
    }

    /**
     * @return the overflow policy for the framework default event executor's bounded queue (#109).
     *         Defaults to {@link OverflowPolicy#CALLER_RUNS}. Ignored when a custom
     *         {@link #eventExecutor()} is supplied.
     */
    public OverflowPolicy onOverflow() {
        return overflowPolicy;
    }

    /**
     * @return the configured core pool size for the framework default event executor (#110),
     *         or {@link #UNSET_POOL_SIZE} when unset (the framework derives {@code Math.max(2,
     *         availableProcessors() / 2)}). Ignored when a custom {@link #eventExecutor()} is supplied.
     */
    public int eventExecutorCoreSize() {
        return eventExecutorCoreSize;
    }

    /**
     * @return the configured maximum pool size for the framework default event executor (#110),
     *         or {@link #UNSET_POOL_SIZE} when unset (the framework derives {@code availableProcessors()
     *         * 4}). Ignored when a custom {@link #eventExecutor()} is supplied.
     */
    public int eventExecutorMaxSize() {
        return eventExecutorMaxSize;
    }

    /**
     * @return the configured idle keep-alive for the framework default event executor's
     *         non-core threads (#110), or {@code null} when unset (the framework default of
     *         60 seconds). Ignored when a custom {@link #eventExecutor()} is supplied.
     */
    public Duration eventExecutorKeepAlive() {
        return eventExecutorKeepAlive;
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
     * Resolves the override registered for {@code type} with a single map lookup, falling
     * back to {@code fallback} when none is registered (#309). The result routes through
     * the class token, so call sites — chiefly generated factory injection sites — carry
     * no unchecked cast. Same key and precedence as {@link #getOverride(Class)}.
     */
    public <T> T resolveOverride(Class<T> type, java.util.function.Supplier<? extends T> fallback) {
        var override = overrides.get(new OverrideKey(type, ""));
        return override != null ? type.cast(override.get()) : fallback.get();
    }

    /**
     * Named-qualifier variant of {@link #resolveOverride(Class, java.util.function.Supplier)} —
     * single lookup on the {@code (type, name)} key, falling back when none is registered.
     */
    public <T> T resolveOverride(Class<T> type, String name, java.util.function.Supplier<? extends T> fallback) {
        Objects.requireNonNull(name, "name");
        var override = overrides.get(new OverrideKey(type, name));
        return override != null ? type.cast(override.get()) : fallback.get();
    }

    /**
     * Registered transport replacements in registration order; empty when none. Keys are the
     * marker classes passed to {@link Builder#replaceTransport}; values are the decorators,
     * pre-wrapped so the framework can apply them to any discovered {@code TransportBootstrap}.
     */
    java.util.Map<Class<?>, java.util.function.Function<TransportBootstrap, TransportBootstrap>>
            transportReplacements() {
        return transportReplacements;
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
        private Duration teardownTimeout;
        private java.util.function.UnaryOperator<EventBus> eventBusDecorator;
        private int queueCapacity = 1024; // framework default; matches DefaultEventExecutorFactory
        private OverflowPolicy overflowPolicy = OverflowPolicy.CALLER_RUNS;
        private int eventExecutorCoreSize = UNSET_POOL_SIZE;
        private int eventExecutorMaxSize = UNSET_POOL_SIZE;
        private Duration eventExecutorKeepAlive;
        private java.util.Map<OverrideKey, java.util.function.Supplier<?>> overrides;
        private java.util.Map<Class<?>, java.util.function.Function<TransportBootstrap, TransportBootstrap>>
                transportReplacements;

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
         *
         * <p>The framework's overflow handling is also inert here: {@link #queueCapacity(int)},
         * {@link #onOverflow(OverflowPolicy)} (including {@code ROUTE_TO_DLQ} dead-letter routing),
         * and the {@code eventExecutor*} pool-sizing knobs apply only to the framework default pool.
         * A user-supplied executor brings its own queue and rejection policy, so a saturated custom
         * executor surfaces its own {@code RejectedExecutionException} rather than an
         * {@code EventDispatchRejected} — overflow and dead-lettering are then yours to handle.
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
         * Maximum time {@link io.tiko.Container#shutdown()} waits for a single SINGLETON
         * {@code @PreDestroy} method or factory-produced {@code AutoCloseable.close()} to return
         * before interrupting it and moving on to the next component.
         *
         * <p>This bounds the <em>per-component teardown hook</em>, a distinct concern from
         * {@link #shutdownTimeout(Duration)} (which bounds the event-executor drain). Leaving it
         * unset preserves today's behavior exactly: hooks run inline on the shutdown thread with
         * no time limit, so a hook that hangs blocks shutdown forever.
         *
         * <p>When set, an overrunning hook is interrupted ({@code Future.cancel(true)}) and its
         * failure is routed as a {@code PreDestroyFailure} / {@code AutoCloseFailure} whose cause
         * is a {@link java.util.concurrent.TimeoutException}, via the {@link ErrorHandler}; shutdown
         * then continues. A hook that ignores interrupts cannot be forcibly killed — the same JVM
         * contract as {@link #shutdownTimeout(Duration)}.
         *
         * <p>Scope: SINGLETON container shutdown only. REQUEST/EVENT scope-exit teardown is
         * unaffected.
         *
         * @param timeout non-negative duration; {@link Duration#ZERO} gives the hook no time and
         *                routes a timeout immediately
         * @throws IllegalArgumentException if {@code timeout} is negative
         * @throws NullPointerException if {@code timeout} is null
         */
        public Builder teardownTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "teardownTimeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("teardownTimeout must not be negative");
            }
            this.teardownTimeout = timeout;
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

        /**
         * Bounds the framework default event executor's work queue (#109). Defaults to {@code 1024}.
         * Pair with {@link #onOverflow(OverflowPolicy)} to choose what happens when it fills.
         *
         * <p>Has <strong>no effect</strong> when {@link #eventExecutor(java.util.concurrent.ExecutorService)}
         * is set — a user-supplied executor brings its own queue.
         *
         * @param capacity positive queue capacity
         * @throws IllegalArgumentException if {@code capacity} is not positive
         */
        public Builder queueCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("queueCapacity must be positive");
            }
            this.queueCapacity = capacity;
            return this;
        }

        /**
         * Selects what the framework default event executor does when its bounded queue is full
         * (#109). Defaults to {@link OverflowPolicy#CALLER_RUNS} (today's behaviour).
         *
         * <p>Has <strong>no effect</strong> when {@link #eventExecutor(java.util.concurrent.ExecutorService)}
         * is set — a user-supplied executor brings its own rejection policy.
         *
         * @param policy the overflow policy
         * @throws NullPointerException if {@code policy} is null
         */
        public Builder onOverflow(OverflowPolicy policy) {
            this.overflowPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the core pool size of the framework default event executor (#110). When unset,
         * the framework derives {@code Math.max(2, availableProcessors() / 2)} — today's behaviour.
         *
         * <p>Has <strong>no effect</strong> when {@link #eventExecutor(java.util.concurrent.ExecutorService)}
         * is set — a user-supplied executor brings its own sizing.
         *
         * @param coreSize non-negative core pool size
         * @throws IllegalArgumentException if {@code coreSize} is negative
         */
        public Builder eventExecutorCoreSize(int coreSize) {
            if (coreSize < 0) {
                throw new IllegalArgumentException("eventExecutorCoreSize must not be negative");
            }
            this.eventExecutorCoreSize = coreSize;
            return this;
        }

        /**
         * Sets the maximum pool size of the framework default event executor (#110). When unset,
         * the framework derives {@code availableProcessors() * 4} — today's behaviour. The
         * effective maximum must be {@code >=} the effective core size, else container start fails.
         *
         * <p>Has <strong>no effect</strong> when {@link #eventExecutor(java.util.concurrent.ExecutorService)}
         * is set — a user-supplied executor brings its own sizing.
         *
         * @param maxSize positive maximum pool size
         * @throws IllegalArgumentException if {@code maxSize} is not positive
         */
        public Builder eventExecutorMaxSize(int maxSize) {
            if (maxSize <= 0) {
                throw new IllegalArgumentException("eventExecutorMaxSize must be positive");
            }
            this.eventExecutorMaxSize = maxSize;
            return this;
        }

        /**
         * Sets the idle keep-alive for the framework default event executor's non-core threads
         * (#110). When unset, the framework default of 60 seconds applies.
         *
         * <p>Has <strong>no effect</strong> when {@link #eventExecutor(java.util.concurrent.ExecutorService)}
         * is set — a user-supplied executor brings its own keep-alive.
         *
         * @param keepAlive non-negative idle keep-alive
         * @throws IllegalArgumentException if {@code keepAlive} is negative
         * @throws NullPointerException if {@code keepAlive} is null
         */
        public Builder eventExecutorKeepAlive(Duration keepAlive) {
            Objects.requireNonNull(keepAlive, "eventExecutorKeepAlive");
            if (keepAlive.isNegative()) {
                throw new IllegalArgumentException("eventExecutorKeepAlive must not be negative");
            }
            this.eventExecutorKeepAlive = keepAlive;
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

        /**
         * Replaces every ServiceLoader-discovered {@link TransportBootstrap} that is an instance of
         * {@code transport} with the result of {@code replacement}, applied between discovery and
         * transport start. Returning {@code null} drops the transport for this container — the
         * disable idiom. {@code replaceTransport(TransportBootstrap.class, t -> null)} disables all
         * transports.
         *
         * <p>This is a <strong>test affordance</strong> in the same family as
         * {@link #override(Class, java.util.function.Supplier)}: it exists so integration tests can
         * substitute a fake transport (e.g. {@code FakeKafkaTransport} over a {@code FakeKafkaBroker})
         * for the generated one. Production configuration belongs in the transport's own config keys.
         * A registration that matches no discovered transport fails fast at {@code Tiko.create(...)}
         * with {@link io.tiko.ContainerInitializationException} — unlike {@code override(...)}, which
         * is inert when unused.
         *
         * <p>Multiple registrations apply in registration order, and later registrations match
         * against the results of earlier ones — a replacement instance can itself be matched and
         * replaced by a later key.
         *
         * @throws IllegalArgumentException if a replacement is already registered for {@code transport}
         * @throws NullPointerException if either argument is null
         */
        public <T extends TransportBootstrap> Builder replaceTransport(
                Class<T> transport, java.util.function.Function<T, TransportBootstrap> replacement) {
            Objects.requireNonNull(transport, "transport");
            Objects.requireNonNull(replacement, "replacement");
            if (transportReplacements == null) {
                transportReplacements = new java.util.LinkedHashMap<>();
            }
            if (transportReplacements.containsKey(transport)) {
                throw new IllegalArgumentException("replaceTransport already registered for " + transport.getName());
            }
            transportReplacements.put(transport, tb -> replacement.apply(transport.cast(tb)));
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
