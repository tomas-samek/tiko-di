package io.tiko.runtime;

import io.tiko.ConfigSource;
import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.TransportBootstrap;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

/**
 * Main entry point for creating Tiko containers.
 *
 * <p>This class provides factory methods for creating container instances.
 * The actual implementation is generated at compile-time by the annotation processor.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Container container = Tiko.create();
 * UserService service = container.get(UserService.class);
 * }</pre>
 */
public final class Tiko {

    /**
     * Canonical parameter signature of the generated container constructor. The annotation
     * processor emits exactly this signature on {@code TikoContainerImpl}, and both
     * {@link #createSingleModuleContainer} and {@link AggregatingContainer#processContainerResource}
     * look it up reflectively. Keep this list in sync with
     * {@code ContainerGenerator.createConstructor()} — adding a parameter touches both
     * generator output and this constant, nothing else.
     */
    static final Class<?>[] CONTAINER_CTOR_PARAM_TYPES = {
        EventBus.class,
        ErrorHandler.class,
        ExecutorService.class,
        boolean.class,
        java.time.Duration.class,
        TikoOptions.class,
    };

    private static final String MAIN_DESCRIPTOR = AggregatingContainer.DEFAULT_DESCRIPTOR;
    private static final String TEST_DESCRIPTOR = "META-INF/tiko/test-container.properties";

    // Lazy holder: defers System.LoggerFinder resolution until a warning is actually logged.
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    /**
     * Whether tiko-config is on the classpath, looked up as a resource of the loader that links
     * {@link ConfigBinding} — no class is loaded to find out.
     */
    private static final boolean TIKO_CONFIG_PRESENT =
            Tiko.class.getClassLoader().getResource("io/tiko/config/runtime/ConfigBootstrap.class") != null;

    private Tiko() {}

    /**
     * Creates a container with all-default options.
     *
     * <p>Equivalent to {@code Tiko.create(TikoOptions.builder().build())}.
     */
    public static Container create() {
        return create(TikoOptions.builder().build());
    }

    /**
     * Creates a container with the given configuration source. Equivalent to
     * {@code Tiko.create(TikoOptions.builder().configSource(source).build())}.
     *
     * @param source the configuration source, never {@code null}
     */
    public static Container create(ConfigSource source) {
        return create(TikoOptions.builder()
                .configSource(Objects.requireNonNull(source, "source"))
                .build());
    }

    /**
     * Creates a container with the supplied options.
     *
     * @param options framework knobs (config source, error handler, ...). Never {@code null}.
     */
    public static Container create(TikoOptions options) {
        Objects.requireNonNull(options, "options");
        // No upfront fail for missing ConfigSource — module-baked
        // META-INF/tiko/defaults.yaml + @Default annotations may cover everything.
        // bindConfigs always discovers defaults first; per-field errors during binding
        // surface specifically what is missing.
        return createInternal(options);
    }

    /**
     * Creates a container and registers a JVM shutdown hook that calls {@code shutdown()} on
     * process exit — for long-lived processes (servers, daemons) that want {@code @PreDestroy} /
     * {@code ApplicationEndingEvent} to fire on {@code Ctrl+C} / {@code SIGTERM} without wiring
     * their own hook.
     *
     * <p>Returns a {@link TikoDaemon}, deliberately <em>not</em> {@link AutoCloseable}: a daemon's
     * lifecycle is owned by the framework (the hook), not a try-with-resources block. Use
     * {@link TikoDaemon#container()} to resolve beans and {@link TikoDaemon#stop()} to shut down
     * explicitly. For caller-managed lifecycles, use {@link #create(TikoOptions)} instead — it
     * returns an AutoCloseable container and installs no hook.
     *
     * @param options framework knobs. Never {@code null}.
     */
    public static TikoDaemon daemon(TikoOptions options) {
        return new TikoDaemon(create(options));
    }

    /** Daemon variant of {@link #create()}; see {@link #daemon(TikoOptions)}. */
    public static TikoDaemon daemon() {
        return daemon(TikoOptions.builder().build());
    }

    /** Daemon variant of {@link #create(ConfigSource)}; see {@link #daemon(TikoOptions)}. */
    public static TikoDaemon daemon(ConfigSource source) {
        return daemon(TikoOptions.builder()
                .configSource(Objects.requireNonNull(source, "source"))
                .build());
    }

    private static Container createInternal(TikoOptions options) {
        try {
            // 1. Resolve the ErrorHandler — user-supplied or the JUL-backed DefaultErrorHandler.
            ErrorHandler errorHandler = options.errorHandler();
            if (errorHandler == null) {
                errorHandler = new DefaultErrorHandler();
            }

            // 2. Create EventBus instance, then apply the optional decorator. Runs before the
            //    container is constructed so subscribers register against the wrapper.
            EventBus eventBus = new LocalEventBus();
            if (options.eventBusDecorator() != null) {
                eventBus = options.eventBusDecorator().apply(eventBus);
            }

            // 3. Detect single vs multi-module scenario. A {@code @TestComponent}-bearing build
            //    emits {@code META-INF/tiko/test-container.properties} (pointing at the
            //    standalone {@code TestContainerImpl_<hash>}) plus a
            //    {@code META-INF/tiko/test-shadows.properties} declaration. Those apply only
            //    when the container opted into test wiring, as every @TikoTest container does.
            //    Otherwise they are ignored with a warning (#497).
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) classLoader = Tiko.class.getClassLoader();

            String descriptorName = selectDescriptor(options, classLoader);
            int moduleCount =
                    ModuleDescriptors.find(descriptorName, classLoader).size();

            // Bind configuration first: the container needs the bound tiko.shutdownTimeout.
            // A no-op (BoundConfigs.NONE) without tiko-config on the classpath.
            BoundConfigs configs = bindConfigs(options.configSource(), classLoader, errorHandler);
            java.time.Duration effectiveShutdownTimeout = effectiveShutdownTimeout(options, configs);

            // In test mode, always route through the aggregator — even with a single module — so
            // AggregatingContainer's shadow-registration phase (test-shadows.properties →
            // TikoOptions overrides) runs. The single-module fast path bypasses shadow registration
            // entirely.
            boolean testMode = TEST_DESCRIPTOR.equals(descriptorName);

            // container is the generated (or aggregating) container; handle is what callers get.
            // The aggregate owns its threads itself. A single-module container is built with the
            // boot's OwnedThreads bound, so its default executor takes its threads from it, and is
            // always wrapped so close() can report the threads that outlive it (#634).
            Container container;
            Container handle;
            if (moduleCount > 1 || testMode) {
                container = new AggregatingContainer(
                        eventBus,
                        errorHandler,
                        options.eventExecutor(),
                        effectiveShutdownTimeout,
                        options,
                        descriptorName);
                handle = container;
            } else {
                // Single module: Direct instantiation (does NOT call start yet)
                var ownedThreads = new OwnedThreads();
                container = createSingleModuleContainer(
                        eventBus,
                        errorHandler,
                        options.eventExecutor(),
                        effectiveShutdownTimeout,
                        options,
                        descriptorName,
                        ownedThreads);
                handle = new TransportAwareContainer(
                        container, java.util.List.of(), ownedThreads, options.threadsOutlivingCloseHandler());
            }

            // 4. Inject config singletons before start(), so @PostConstruct can use them.
            // Defaults from META-INF/tiko/defaults.yaml are always layered under the user
            // source — modules can ship a self-sufficient bean even when the user provides
            // no ConfigSource. The framework's own record is consumed above, not injected.
            if (!configs.userConfigs().isEmpty()) {
                container.getClass().getMethod("injectConfigs", Map.class).invoke(container, configs.userConfigs());
            }

            // 5. Start the container — single-module's TikoContainerImpl.start() initialises
            // all SINGLETON components and publishes ApplicationStartedEvent;
            // multi-module's AggregatingContainer.start() publishes ApplicationStartedEvent
            // once on the shared bus and leaves per-module singleton init lazy (#45).
            startOrShutdown(handle);

            // 6. Discover transport modules (tiko-kafka, future tiko-http, ...) and start them.
            //    A failure discovering or starting any transport must not leak the
            //    already-started container (#348) — tear it down before the exception escapes.
            return startTransportsOrShutdown(handle, classLoader, options);
        } catch (RuntimeException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            throw new ContainerInitializationException(
                    "Tiko container implementation not found. Did you include tiko-processor in your annotation processor path?",
                    e);
        } catch (Exception e) {
            throw new ContainerInitializationException("Failed to create container instance", e);
        }
    }

    /**
     * Binds the framework's {@code tiko.*} keys and every {@code @Configuration} record whose
     * binder is listed in {@code META-INF/services/io.tiko.config.ConfigBinder} (#531).
     *
     * <p>Layers module-baked {@code META-INF/tiko/defaults.yaml} under the (optional)
     * user source so each module can ship its own private slice of defaults inside
     * its jar — overrideable per-key by the user file (#18).</p>
     *
     * <p>Returns {@link BoundConfigs#NONE} when tiko-config is not on the classpath; then
     * {@link ConfigBinding}, which needs it, is never loaded.</p>
     */
    static BoundConfigs bindConfigs(ConfigSource userSource, ClassLoader cl, ErrorHandler errorHandler) {
        return TIKO_CONFIG_PRESENT ? ConfigBinding.bind(userSource, cl, errorHandler) : BoundConfigs.NONE;
    }

    /**
     * The event-executor shutdown timeout, with precedence: programmatic
     * ({@link TikoOptions#shutdownTimeout()}) > {@code tiko.shutdownTimeout} bound from config
     * (defaults to {@code PT10S} there) > {@code Duration.ofSeconds(10)} without tiko-config.
     */
    static java.time.Duration effectiveShutdownTimeout(TikoOptions options, BoundConfigs configs) {
        if (options.shutdownTimeout() != null) return options.shutdownTimeout();
        if (configs.shutdownTimeout() != null) return configs.shutdownTimeout();
        return java.time.Duration.ofSeconds(10);
    }

    /**
     * The container descriptor to boot from: the test descriptor when the container opted into
     * test wiring and one is on the classpath, otherwise the main one. Test wiring found without
     * the opt-in is reported, never applied (#497).
     */
    private static String selectDescriptor(TikoOptions options, ClassLoader classLoader) throws IOException {
        var testWiring = new ArrayList<URL>();
        testWiring.addAll(Collections.list(classLoader.getResources(TEST_DESCRIPTOR)));
        if (options.testWiring()) {
            return testWiring.isEmpty() ? MAIN_DESCRIPTOR : TEST_DESCRIPTOR;
        }
        testWiring.addAll(Collections.list(classLoader.getResources(AggregatingContainer.TEST_SHADOWS)));
        if (!testWiring.isEmpty()) {
            TikoLog.log(
                    LoggerHolder.LOG,
                    System.Logger.Level.WARNING,
                    "Ignoring test wiring on the classpath: {0}. Test components apply only when the container"
                            + " opts in: @TikoTest, or TikoOptions.builder().testWiring(true).",
                    testWiring);
        }
        return MAIN_DESCRIPTOR;
    }

    /**
     * Creates a single-module container. Does NOT call start() — that is done in createInternal
     * after injectConfigs() runs.
     */
    private static Container createSingleModuleContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            ExecutorService userEventExecutor,
            java.time.Duration shutdownTimeout,
            TikoOptions options,
            String descriptorName,
            OwnedThreads ownedThreads)
            throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = Tiko.class.getClassLoader();

        var resources = ModuleDescriptors.find(descriptorName, classLoader);
        Class<?> implClass;
        if (!resources.isEmpty()) {
            Properties props = new Properties();
            try (var input = new InputStreamReader(resources.get(0).openStream(), StandardCharsets.UTF_8)) {
                props.load(input);
            }
            String implClassName = props.getProperty("impl");
            implClass = Class.forName(implClassName);
        } else {
            implClass = Class.forName("io.tiko.generated.TikoContainerImpl");
        }

        // Single-module: publishLifecycleEvents=true so the per-module container publishes
        // its own ApplicationStartedEvent / ApplicationEndingEvent (no aggregator above it).
        // The 6th arg (TikoOptions) is held by the container so override-aware getters can
        // consult it during component lookup (test/runtime override support).
        // Constructed with the boot's OwnedThreads bound: the generated constructor builds the
        // default event executor, which captures that factory for its worker threads (#634).
        var constructor = implClass.getDeclaredConstructor(CONTAINER_CTOR_PARAM_TYPES);
        Container container = OwnedThreads.callBound(ownedThreads, () -> (Container) constructor.newInstance(
                eventBus,
                errorHandler,
                userEventExecutor,
                /* publishLifecycleEvents */ true,
                shutdownTimeout,
                options));

        registerEventHandlers(eventBus, container, implClass);

        // NOTE: do NOT call start() here — createInternal calls it AFTER injectConfigs
        return container;
    }

    /**
     * Covers containers generated by older processors that do not roll back their own startup.
     * Rollback also runs on {@link Error}. Any cleanup failure is suppressed on the startup failure.
     */
    @SuppressWarnings("java:S1181")
    private static void startOrShutdown(Container container) {
        try {
            container.start();
        } catch (Throwable startupFailure) {
            try {
                container.shutdown();
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != startupFailure) {
                    startupFailure.addSuppressed(cleanupFailure);
                }
            }
            throw startupFailure;
        }
    }

    /**
     * Builds the transport-aware wrapper and starts every discovered {@link TransportBootstrap}.
     * Returns the bare container when none were discovered. On any start failure, stops the
     * transports already started (reverse order) and shuts the container down before rethrowing,
     * so a failed bootstrap never leaves a started-but-unreachable container behind (#348).
     */
    static Container startTransports(Container container, java.util.List<TransportBootstrap> bootstraps) {
        if (bootstraps.isEmpty()) {
            return container;
        }
        // Build the wrapper first so start() callers receive the public-facing handle. A
        // single-module boot is already wrapped; it gains the transports instead of a second layer.
        TransportAwareContainer wrapper = container instanceof TransportAwareContainer booted
                ? booted.withTransports(bootstraps)
                : new TransportAwareContainer(container, bootstraps, null, null);
        int started = 0;
        try {
            for (TransportBootstrap tb : bootstraps) {
                tb.start(wrapper);
                started++;
            }
            return wrapper;
        } catch (RuntimeException | LinkageError e) {
            // RuntimeException covers normal start failures (bad config); LinkageError covers a
            // half-present transport jar (missing transitive dep). Either way, unwind cleanly.
            for (int i = started - 1; i >= 0; i--) {
                shutdownQuietly(bootstraps.get(i));
            }
            shutdownQuietly(container, e);
            throw e;
        }
    }

    /**
     * Discovers transports via {@code ServiceLoader}, applies any {@link TikoOptions.Builder#replaceTransport}
     * registrations, and hands off to {@link #startTransports}. A {@link java.util.ServiceConfigurationError}
     * raised while iterating providers, or a failure applying a replacement, shuts the already-started
     * container down before propagating (#348).
     */
    private static Container startTransportsOrShutdown(
            Container container, ClassLoader classLoader, TikoOptions options) {
        java.util.List<TransportBootstrap> bootstraps = new java.util.ArrayList<>();
        try {
            for (TransportBootstrap tb : java.util.ServiceLoader.load(TransportBootstrap.class, classLoader)) {
                bootstraps.add(tb);
            }
            bootstraps = applyTransportReplacements(bootstraps, options);
        } catch (RuntimeException | java.util.ServiceConfigurationError e) {
            shutdownQuietly(container, e);
            throw e;
        }
        return startTransports(container, bootstraps);
    }

    /**
     * Applies {@link TikoOptions.Builder#replaceTransport} registrations to the discovered
     * transports, in registration order. Each entry must match at least one discovered
     * transport ({@code Class.isInstance}); a {@code null} decorator result drops the
     * transport. An entry may match several transports — the decorator runs for each.
     */
    static java.util.List<TransportBootstrap> applyTransportReplacements(
            java.util.List<TransportBootstrap> discovered, TikoOptions options) {
        var replacements = options.transportReplacements();
        if (replacements.isEmpty()) {
            return discovered;
        }
        java.util.List<TransportBootstrap> result = new java.util.ArrayList<>(discovered);
        for (var entry : replacements.entrySet()) {
            Class<?> key = entry.getKey();
            java.util.function.UnaryOperator<TransportBootstrap> decorator = entry.getValue();
            boolean matched = false;
            for (int i = 0; i < result.size(); i++) {
                TransportBootstrap current = result.get(i);
                if (current == null || !key.isInstance(current)) {
                    continue;
                }
                matched = true;
                try {
                    result.set(i, decorator.apply(current));
                } catch (RuntimeException e) {
                    throw new ContainerInitializationException(
                            "replaceTransport(" + key.getName() + ", ...) threw while replacing "
                                    + current.getClass().getName(),
                            e);
                }
            }
            if (!matched) {
                throw unmatchedTransportKey(key, result);
            }
        }
        // Nulls are kept in place during the loop so indexes stay stable and later entries
        // still see the surviving transports; removed once at the end.
        result.removeIf(java.util.Objects::isNull);
        return result;
    }

    /**
     * Builds the fail-fast exception for a {@link TikoOptions.Builder#replaceTransport} key that
     * matched none of the discovered transports, naming what was actually discovered.
     */
    private static ContainerInitializationException unmatchedTransportKey(
            Class<?> key, java.util.List<TransportBootstrap> result) {
        java.util.List<String> discoveredNames = result.stream()
                .filter(java.util.Objects::nonNull)
                .map(tb -> tb.getClass().getName())
                .toList();
        return new ContainerInitializationException("replaceTransport(" + key.getName()
                + ", ...) matched no discovered transport.\n"
                + "Discovered transports: "
                + (discoveredNames.isEmpty() ? "(none)" : discoveredNames)
                + "\n"
                + "Suggested fixes:\n"
                + "1. Check the transport module (runtime + annotation processor) is on the classpath.\n"
                + "2. Remove the replaceTransport(...) registration if the transport is not part of this app.");
    }

    /**
     * Best-effort container teardown on a failing bootstrap path — never masks the original failure.
     * Anything the teardown throws, an {@link Error} from a thread-report handler included, is
     * suppressed on {@code bootFailure}.
     */
    @SuppressWarnings("java:S1181") // unwinding a bootstrap failure; the original exception is the one that matters
    private static void shutdownQuietly(Container container, Throwable bootFailure) {
        try {
            container.shutdown();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != bootFailure) {
                bootFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /** Best-effort transport teardown on a failing bootstrap path. */
    private static void shutdownQuietly(TransportBootstrap bootstrap) {
        try {
            bootstrap.shutdown();
        } catch (Exception ignored) {
            /* best-effort */
        }
    }

    /**
     * Registers event handlers if {@code EventRegistry_<hash>} is present.
     *
     * <p>The generated registry class is named after the container — {@code EventRegistry_}
     * suffixed with the container's hash — so multi-module classpaths (and standalone test
     * containers peering with a main container) can each carry their own handler set
     * without colliding on a single {@code io.tiko.generated.EventRegistry} slot.
     */
    static void registerEventHandlers(EventBus eventBus, Container container, Class<?> containerClass) {
        String containerName = containerClass.getSimpleName();
        int underscore = containerName.lastIndexOf('_');
        String suffix = underscore >= 0 ? containerName.substring(underscore) : "";
        String registryFqn = "io.tiko.generated.EventRegistry" + suffix;

        Class<?> registryClass;
        try {
            registryClass = Class.forName(registryFqn, true, containerClass.getClassLoader());
        } catch (ClassNotFoundException e) {
            // No event handlers registered for this container - that's fine
            return;
        }
        // Once the registry class is present, registration is NOT optional — the absent-registry
        // case was already handled by the ClassNotFoundException return above. A failure here is a
        // real defect (version drift, or a subscribe throwing on a decorated bus); surface it
        // loudly naming the registry, rather than silently dropping every handler in the module (#348).
        java.lang.reflect.Method registerMethod;
        try {
            registerMethod = registryClass.getMethod("registerHandlers", EventBus.class, containerClass);
        } catch (NoSuchMethodException e) {
            throw new ContainerInitializationException(
                    registryFqn + " has no registerHandlers(EventBus, " + containerClass.getSimpleName()
                            + ") method — rebuild with matching tiko-processor and tiko-runtime versions.",
                    e);
        }
        try {
            registerMethod.invoke(null, eventBus, container);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ContainerInitializationException(
                    "Event handler registration failed in " + registryFqn + ": " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new ContainerInitializationException(registryFqn + ".registerHandlers is not accessible.", e);
        }
    }

    /**
     * The handle {@code Tiko.create(...)} returns when the container needs one: a single-module
     * boot always, any boot with transports. On {@code shutdown()} it runs every
     * {@link TransportBootstrap#shutdown()} before the container's own {@code shutdown()}.
     *
     * <p>For a single-module boot it also owns the boot's {@link OwnedThreads} (#634): its
     * {@link #threadFactory()} tracks, the container's start and shutdown run bound so teardown
     * threads, those of a failed start's rollback included (#651), are tracked, and the threads
     * still alive afterwards are reported once. Over an aggregated boot
     * {@code threads} is {@code null}: the aggregate owns and reports its threads itself.
     *
     * <p>Method delegation is exhaustive, default methods included, so the wrapper changes nothing
     * but the identity of the returned object. We cannot use {@code Container} as a sealed type
     * because user-supplied implementations are not on the radar of this module.
     */
    private static final class TransportAwareContainer implements Container {
        private final Container delegate;
        private final java.util.List<TransportBootstrap> bootstraps;
        private final OwnedThreads threads;
        private final java.util.function.Consumer<java.util.List<Thread>> onThreadsOutlivingClose;

        TransportAwareContainer(
                Container delegate,
                java.util.List<TransportBootstrap> bootstraps,
                OwnedThreads threads,
                java.util.function.Consumer<java.util.List<Thread>> onThreadsOutlivingClose) {
            this.delegate = delegate;
            this.bootstraps = bootstraps;
            this.threads = threads;
            this.onThreadsOutlivingClose = onThreadsOutlivingClose;
        }

        /** The same container and thread owner, now also stopping {@code transports} on shutdown. */
        TransportAwareContainer withTransports(java.util.List<TransportBootstrap> transports) {
            return new TransportAwareContainer(delegate, transports, threads, onThreadsOutlivingClose);
        }

        @Override
        public <T> T get(Class<T> type) {
            return delegate.get(type);
        }

        @Override
        public <T> T get(Class<T> type, String name) {
            return delegate.get(type, name);
        }

        @Override
        public <T> java.util.List<T> getAll(Class<T> type) {
            return delegate.getAll(type);
        }

        @Override
        public <T> io.tiko.Provider<T> getProvider(Class<T> type) {
            return delegate.getProvider(type);
        }

        @Override
        public <T> io.tiko.Provider<T> getProvider(Class<T> type, String name) {
            return delegate.getProvider(type, name);
        }

        @Override
        public <T> io.tiko.PickBuilder<T> pick(Class<T> type) {
            return delegate.pick(type);
        }

        @Override
        public void runInEventScope(Runnable runnable) {
            delegate.runInEventScope(runnable);
        }

        @Override
        public <T> T supplyInEventScope(java.util.function.Supplier<T> s) {
            return delegate.supplyInEventScope(s);
        }

        /**
         * Runs bound, like {@link #shutdown()}: a failing generated {@code start()} rolls the module
         * back from inside itself, and that rollback's teardown threads must be tracked too (#651).
         * Over an aggregate {@code threads} is {@code null}, which binds no owner.
         */
        @Override
        public void start() {
            OwnedThreads.runBound(threads, delegate::start);
        }

        @Override
        public io.tiko.EventBus getEventBus() {
            return delegate.getEventBus();
        }

        @Override
        public io.tiko.EventBus events() {
            return delegate.events();
        }

        @Override
        public java.util.concurrent.ExecutorService getEventExecutor() {
            return delegate.getEventExecutor();
        }

        @Override
        public java.util.Optional<io.tiko.ExecutorMetrics> eventExecutorMetrics() {
            return delegate.eventExecutorMetrics();
        }

        @Override
        public io.tiko.ErrorHandler getErrorHandler() {
            return delegate.getErrorHandler();
        }

        @Override
        public java.util.concurrent.ThreadFactory threadFactory() {
            return threads != null ? threads : delegate.threadFactory();
        }

        @Override
        @SuppressWarnings("java:S1181") // Error included: the thread report runs before it is rethrown.
        public void shutdown() {
            // Shut transports down BEFORE the container's @PreDestroy chain so their bridge
            // components are still live. Per-bootstrap throws are isolated so one bad
            // transport cannot strand another's resources.
            for (TransportBootstrap tb : bootstraps) {
                try {
                    tb.shutdown();
                } catch (Exception ignored) {
                    /* best-effort */
                }
            }
            if (threads == null) {
                delegate.shutdown();
                return;
            }
            Throwable shutdownFailure = null;
            try {
                OwnedThreads.runBound(threads, delegate::shutdown);
            } catch (Throwable t) {
                shutdownFailure = t;
            }
            // The report never masks the shutdown failure: a throwing handler is suppressed on it.
            threads.reportSurvivors(onThreadsOutlivingClose, shutdownFailure);
            if (shutdownFailure != null) {
                throw Unchecked.<RuntimeException>sneakyThrow(shutdownFailure);
            }
        }

        @Override
        public void close() {
            // Through this wrapper's shutdown(), never the delegate's close(): that would skip the
            // transports and the report.
            shutdown();
        }
    }
}
