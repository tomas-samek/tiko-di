package io.tiko.runtime;

import io.tiko.ConfigSource;
import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.TransportBootstrap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
            int moduleCount = countResources(classLoader.getResources(descriptorName));

            // Bind configuration first: the container needs the bound tiko.shutdownTimeout.
            // A no-op (BoundConfigs.NONE) without tiko-config on the classpath.
            BoundConfigs configs = bindConfigs(options.configSource(), classLoader, errorHandler);
            java.time.Duration effectiveShutdownTimeout = effectiveShutdownTimeout(options, configs);

            // In test mode, always route through the aggregator — even with a single module — so
            // AggregatingContainer's shadow-registration phase (test-shadows.properties →
            // TikoOptions overrides) runs. The single-module fast path bypasses shadow registration
            // entirely.
            boolean testMode = TEST_DESCRIPTOR.equals(descriptorName);

            Container container;
            if (moduleCount > 1 || testMode) {
                container = new AggregatingContainer(
                        eventBus,
                        errorHandler,
                        options.eventExecutor(),
                        effectiveShutdownTimeout,
                        options,
                        descriptorName);
            } else {
                // Single module: Direct instantiation (does NOT call start yet)
                container = createSingleModuleContainer(
                        eventBus,
                        errorHandler,
                        options.eventExecutor(),
                        effectiveShutdownTimeout,
                        options,
                        descriptorName);
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
            container.start();

            // 6. Discover transport modules (tiko-kafka, future tiko-http, ...) and start them.
            //    A failure discovering or starting any transport must not leak the
            //    already-started container (#348) — tear it down before the exception escapes.
            return startTransportsOrShutdown(container, classLoader, options);
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
     * Loads and binds all declared @Configuration records from configs.txt manifests.
     *
     * <p>Layers module-baked {@code META-INF/tiko/defaults.yaml} under the (optional)
     * user source so each module can ship its own private slice of defaults inside
     * its jar — overrideable per-key by the user file (#18). The framework's own
     * {@code tiko.*} keys bind the same way, through tiko-config's
     * {@code TikoFrameworkConfig} (#114).</p>
     *
     * <p>Returns {@link BoundConfigs#NONE} when tiko-config is not on the classpath; then
     * {@link ConfigBinding}, which needs it, is never loaded.</p>
     */
    static BoundConfigs bindConfigs(ConfigSource userSource, ClassLoader cl, ErrorHandler errorHandler)
            throws Exception {
        // A registry named by several manifests (a fat jar next to the jars it bundles) is the
        // same binders: load each one once, or its prefixes would be reported as duplicates.
        var registries = new LinkedHashSet<String>();
        var manifests = cl.getResources("META-INF/tiko/configs.txt");
        while (manifests.hasMoreElements()) {
            String registry = registryName(manifests.nextElement());
            if (registry != null) registries.add(registry);
        }
        if (!TIKO_CONFIG_PRESENT) return BoundConfigs.NONE;
        List<io.tiko.config.ConfigBinder<?>> binders = new ArrayList<>();
        for (String registry : registries) {
            binders.addAll(registryBinders(registry, cl));
        }
        return ConfigBinding.bind(userSource, binders, errorHandler);
    }

    /** The registry class a {@code configs.txt} names on its {@code # registry=} line, or {@code null}. */
    private static String registryName(URL manifest) throws IOException {
        try (BufferedReader br =
                new BufferedReader(new InputStreamReader(manifest.openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("# registry="))
                    return line.substring("# registry=".length()).trim();
            }
        }
        return null;
    }

    /**
     * The binders a registry advertises. The registry is generated (or hand-maintained) per
     * module; its static {@code all()} is its only entry point, located by the name its manifest
     * records.
     */
    @SuppressWarnings("unchecked")
    private static List<io.tiko.config.ConfigBinder<?>> registryBinders(String registry, ClassLoader cl)
            throws Exception {
        Class<?> registryClass = Class.forName(registry, true, cl);
        return (List<io.tiko.config.ConfigBinder<?>>)
                registryClass.getMethod("all").invoke(null);
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

    /** Counts how many {@link java.net.URL}s an enumeration yields, draining it. */
    private static int countResources(java.util.Enumeration<java.net.URL> resources) {
        int count = 0;
        while (resources.hasMoreElements()) {
            resources.nextElement();
            count++;
        }
        return count;
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
            String descriptorName)
            throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = Tiko.class.getClassLoader();

        var resources = classLoader.getResources(descriptorName);
        Class<?> implClass;
        if (resources.hasMoreElements()) {
            Properties props = new Properties();
            try (var input = resources.nextElement().openStream()) {
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
        Container container = (Container) implClass
                .getDeclaredConstructor(CONTAINER_CTOR_PARAM_TYPES)
                .newInstance(
                        eventBus,
                        errorHandler,
                        userEventExecutor,
                        /* publishLifecycleEvents */ true,
                        shutdownTimeout,
                        options);

        registerEventHandlers(eventBus, container, implClass);

        // NOTE: do NOT call start() here — createInternal calls it AFTER injectConfigs
        return container;
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
        // Build the wrapper first so start() callers receive the public-facing handle.
        TransportAwareContainer wrapper = new TransportAwareContainer(container, bootstraps);
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
            shutdownQuietly(container);
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
            shutdownQuietly(container);
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

    /** Best-effort container teardown on a failing bootstrap path — never masks the original failure. */
    private static void shutdownQuietly(Container container) {
        try {
            container.shutdown();
        } catch (Exception ignored) {
            /* unwinding a bootstrap failure; the original exception is the one that matters */
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
     * Wrapper that runs every {@link TransportBootstrap#shutdown()} before delegating to the
     * underlying container's own {@code shutdown()} / {@code close()}. Method delegation is
     * exhaustive; we cannot use {@code Container} as a sealed type because user-supplied
     * implementations are not on the radar of this module.
     */
    private static final class TransportAwareContainer implements Container {
        private final Container delegate;
        private final java.util.List<TransportBootstrap> bootstraps;

        TransportAwareContainer(Container delegate, java.util.List<TransportBootstrap> bootstraps) {
            this.delegate = delegate;
            this.bootstraps = bootstraps;
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
        public void runInEventScope(Runnable runnable) {
            delegate.runInEventScope(runnable);
        }

        @Override
        public <T> T supplyInEventScope(java.util.function.Supplier<T> s) {
            return delegate.supplyInEventScope(s);
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public io.tiko.EventBus getEventBus() {
            return delegate.getEventBus();
        }

        @Override
        public java.util.concurrent.ExecutorService getEventExecutor() {
            return delegate.getEventExecutor();
        }

        @Override
        public io.tiko.ErrorHandler getErrorHandler() {
            return delegate.getErrorHandler();
        }

        @Override
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
            delegate.shutdown();
        }
    }
}
