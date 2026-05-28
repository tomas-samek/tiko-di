package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.NoSuchComponentException;
import io.tiko.Provider;
import io.tiko.events.ApplicationEndingEvent;
import io.tiko.events.ApplicationStartedEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Aggregating container that delegates to multiple module-specific containers.
 *
 * <p>This container discovers all Tiko containers on the classpath by scanning for
 * META-INF/tiko/container.properties files. Each module generates its own container
 * implementation with a unique class name, and this aggregator coordinates between them.</p>
 *
 * <p>Thread-safe and supports all standard container operations across multiple modules.</p>
 */
public final class AggregatingContainer implements Container {

    /** Default descriptor name used when callers don't override (production / multi-module). */
    static final String DEFAULT_DESCRIPTOR = "META-INF/tiko/container.properties";

    /**
     * Lazy holder: defers System.LoggerFinder resolution until first use. Most aggregator
     * paths log only on bus-impl defects or shadow-config issues, so the class's {@code <clinit>}
     * stays free of logging-init cost.
     */
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    private final EventBus sharedEventBus;
    private final ErrorHandler errorHandler;
    private final java.util.concurrent.ExecutorService eventExecutor;
    private final boolean ownsEventExecutor;
    private final Duration shutdownTimeout;
    private final TikoOptions options;
    private final String descriptorName;
    private final List<Container> moduleContainers;
    private final Map<Class<?>, Container> componentToContainerMap;
    private final Map<Class<?>, Container> configToContainer = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdownInvoked = new AtomicBoolean(false);
    private final AtomicBoolean startInvoked = new AtomicBoolean(false);
    private volatile Instant startedAt;

    /**
     * Creates an aggregating container with a custom error handler and event executor.
     *
     * <p>The supplied {@code userEventExecutor} (or a framework default if {@code null})
     * is materialised once and shared across all per-module containers (#51), so async
     * events submitted from any module use the same pool.
     *
     * @param eventBus         shared event bus instance passed to all module containers
     * @param errorHandler     error handler for event handler exceptions
     * @param userEventExecutor optional user-supplied event executor. When {@code null},
     *                         the aggregator creates a default {@link DefaultEventExecutorFactory}
     *                         instance and owns its lifecycle (shuts it down on
     *                         {@link #shutdown()}). When non-{@code null}, the user owns
     *                         the lifecycle — the aggregator never shuts it down.
     * @throws ContainerInitializationException if container discovery or initialization fails
     */
    public AggregatingContainer(
            EventBus eventBus, ErrorHandler errorHandler, java.util.concurrent.ExecutorService userEventExecutor) {
        this(
                eventBus,
                errorHandler,
                userEventExecutor,
                Duration.ofSeconds(10),
                TikoOptions.builder().build(),
                DEFAULT_DESCRIPTOR);
    }

    /**
     * Creates an aggregating container with a custom error handler, event executor, and
     * shutdown timeout. The {@code shutdownTimeout} caps how long {@link #shutdown()} waits
     * for the framework-owned executor to drain gracefully before calling {@code shutdownNow()}.
     * Has no effect when {@code userEventExecutor} is non-null (user owns its lifecycle).
     *
     * @param eventBus            shared event bus
     * @param errorHandler        error handler for event handler exceptions
     * @param userEventExecutor   optional user-supplied executor; null means framework-owned
     * @param shutdownTimeout     graceful drain window; non-negative
     */
    public AggregatingContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            java.util.concurrent.ExecutorService userEventExecutor,
            Duration shutdownTimeout) {
        this(
                eventBus,
                errorHandler,
                userEventExecutor,
                shutdownTimeout,
                TikoOptions.builder().build(),
                DEFAULT_DESCRIPTOR);
    }

    /**
     * Canonical constructor — accepts the user's {@link TikoOptions} so per-module
     * containers can consult overrides from generated getter methods. The
     * {@code shutdownTimeout} caps how long {@link #shutdown()} waits for the
     * framework-owned executor to drain gracefully before calling {@code shutdownNow()}.
     * Has no effect when {@code userEventExecutor} is non-null (user owns its lifecycle).
     *
     * @param eventBus            shared event bus
     * @param errorHandler        error handler for event handler exceptions
     * @param userEventExecutor   optional user-supplied executor; null means framework-owned
     * @param shutdownTimeout     graceful drain window; non-negative
     * @param options             framework options; forwarded to every per-module container
     */
    public AggregatingContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            java.util.concurrent.ExecutorService userEventExecutor,
            Duration shutdownTimeout,
            TikoOptions options) {
        this(eventBus, errorHandler, userEventExecutor, shutdownTimeout, options, DEFAULT_DESCRIPTOR);
    }

    /**
     * Variant accepting an explicit descriptor resource name. Used by
     * {@link Tiko#createInternal} to pass {@code META-INF/tiko/test-container.properties}
     * when test-side wiring is on the classpath; production-path callers go through the
     * five-arg overload which keeps the historical {@code container.properties} default.
     */
    public AggregatingContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            java.util.concurrent.ExecutorService userEventExecutor,
            Duration shutdownTimeout,
            TikoOptions options,
            String descriptorName) {
        this.sharedEventBus = eventBus;
        this.errorHandler = errorHandler;
        this.eventExecutor = userEventExecutor != null ? userEventExecutor : DefaultEventExecutorFactory.create();
        this.ownsEventExecutor = (userEventExecutor == null);
        this.shutdownTimeout = shutdownTimeout;
        this.options = options;
        this.descriptorName = descriptorName;
        this.moduleContainers = new ArrayList<>();
        this.componentToContainerMap = new ConcurrentHashMap<>();

        try {
            discoverAndInitializeModuleContainers();
        } catch (Exception e) {
            throw new ContainerInitializationException("Failed to initialize aggregating container", e);
        }
    }

    /**
     * Discovers all module containers by scanning the configured descriptor resource
     * (defaults to {@value #DEFAULT_DESCRIPTOR}; {@link Tiko#createInternal} passes
     * {@code META-INF/tiko/test-container.properties} when test wiring is present).
     *
     * <p>Two-phase scan (#129):
     * <ol>
     *   <li>Phase 1 — scan classpath for {@code META-INF/tiko/test-shadows.properties}
     *       and register each declared {@code key → impl} pair as an {@code IfAbsent}
     *       runtime override on the shared {@link TikoOptions}. The Supplier closes
     *       over a map populated in Phase 2; user-supplied overrides win because of
     *       {@code IfAbsent} semantics.</li>
     *   <li>Phase 2 — instantiate per-module containers from the descriptor resource;
     *       each instance is registered in {@code containersByImplName} so the
     *       Phase 1 Suppliers can resolve their shadow targets lazily on first
     *       {@code get(...)}.</li>
     * </ol>
     */
    private void discoverAndInitializeModuleContainers() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = AggregatingContainer.class.getClassLoader();
        }

        // Shared map populated in Phase 2 — shadow-override Suppliers (registered in Phase 1)
        // close over it and resolve their target container lazily on first invocation.
        Map<String, Container> containersByImplName = new HashMap<>();

        // Phase 1: scan classpath for test-shadows.properties and register shadow overrides.
        Enumeration<URL> shadowResources = classLoader.getResources("META-INF/tiko/test-shadows.properties");
        Map<String, String> seenShadowDeclarations = new HashMap<>();
        while (shadowResources.hasMoreElements()) {
            URL url = shadowResources.nextElement();
            Properties shadowProps = new Properties();
            try (var in = url.openStream()) {
                shadowProps.load(in);
            }
            for (var entry : shadowProps.entrySet()) {
                String routableKey = entry.getKey().toString();
                String rawValue = entry.getValue().toString();
                if (seenShadowDeclarations.containsKey(routableKey)) {
                    LoggerHolder.LOG.log(
                            System.Logger.Level.WARNING,
                            "Multiple shadow declarations for " + routableKey
                                    + " (first: " + seenShadowDeclarations.get(routableKey)
                                    + ", ignored: " + rawValue + ")");
                    continue;
                }
                // Format (since #129 T8): "testContainerFqn|testComponentFqn". The legacy
                // single-FQN form is tolerated by treating the whole value as the container
                // and falling back to keyClass dispatch — which can recurse if the test
                // container's override is hit again — so the split form is preferred.
                int pipe = rawValue.indexOf('|');
                String shadowContainerFqn = pipe >= 0 ? rawValue.substring(0, pipe) : rawValue;
                String shadowComponentFqn = pipe >= 0 ? rawValue.substring(pipe + 1) : routableKey;
                seenShadowDeclarations.put(routableKey, rawValue);
                Class<?> keyClass;
                Class<?> componentClass;
                try {
                    keyClass = Class.forName(routableKey, false, classLoader);
                    componentClass = Class.forName(shadowComponentFqn, false, classLoader);
                } catch (ClassNotFoundException cnfe) {
                    LoggerHolder.LOG.log(
                            System.Logger.Level.WARNING,
                            "Shadow declaration references unknown type: " + cnfe.getMessage());
                    continue;
                }
                registerShadowOverride(keyClass, componentClass, shadowContainerFqn, containersByImplName);
            }
        }

        // Phase 2: instantiate per-module containers from descriptor resources.
        Enumeration<URL> resources = classLoader.getResources(descriptorName);

        while (resources.hasMoreElements()) {
            URL resourceUrl = resources.nextElement();
            processContainerResource(resourceUrl, classLoader, containersByImplName);
        }

        // In test mode (descriptorName = test-container.properties), the test container only
        // holds test-side components; main components live on the production container at
        // META-INF/tiko/container.properties on the compile classpath. Process those too so
        // routing covers both sides — shadow overrides registered in Phase 1 ensure the test
        // container's components win for shadowed keys.
        if (!DEFAULT_DESCRIPTOR.equals(descriptorName)) {
            Enumeration<URL> mainResources = classLoader.getResources(DEFAULT_DESCRIPTOR);
            while (mainResources.hasMoreElements()) {
                URL resourceUrl = mainResources.nextElement();
                processContainerResource(resourceUrl, classLoader, containersByImplName);
            }
        }

        if (moduleContainers.isEmpty()) {
            throw new ContainerInitializationException(
                    "No Tiko containers found on classpath. Expected at least one " + descriptorName + " file.");
        }
    }

    /**
     * Registers a single shadow declaration as an {@code IfAbsent} override on the shared
     * {@link TikoOptions}. The Supplier is lazy: it resolves the target container on first
     * {@code get(...)} via {@code containersByImplName}, which is populated during Phase 2
     * of {@link #discoverAndInitializeModuleContainers()}.
     *
     * <p>The shadow resolves to {@code shadowContainer.get(componentClass)} — addressing
     * the test component by its own class so the override on {@code keyClass} does not
     * recurse when the test container's own {@code get(keyClass)} dispatcher consults the
     * same override again.
     */
    @SuppressWarnings("unchecked")
    private <T> void registerShadowOverride(
            Class<T> keyClass,
            Class<?> componentClass,
            String shadowContainerFqn,
            Map<String, Container> containersByImplName) {
        java.util.function.Supplier<T> supplier = () -> {
            Container shadowContainer = containersByImplName.get(shadowContainerFqn);
            if (shadowContainer == null) {
                throw new ContainerInitializationException(
                        "Shadow declaration target not instantiated: " + shadowContainerFqn);
            }
            return (T) shadowContainer.get(componentClass);
        };
        options.internalAddOverrideIfAbsent(keyClass, supplier);
    }

    /**
     * Processes a single container.properties resource. The instantiated container is
     * registered in {@code containersByImplName} so shadow-override Suppliers registered
     * in Phase 1 of {@link #discoverAndInitializeModuleContainers()} can resolve their
     * target lazily.
     */
    private void processContainerResource(
            URL resourceUrl, ClassLoader classLoader, Map<String, Container> containersByImplName) throws Exception {
        // Read container.properties to get impl class name
        Properties props = new Properties();
        try (var input = resourceUrl.openStream()) {
            props.load(input);
        }

        String implClassName = props.getProperty("impl");
        if (implClassName == null || implClassName.trim().isEmpty()) {
            throw new ContainerInitializationException("Missing 'impl' property in " + resourceUrl);
        }

        // Load and instantiate the container with the canonical constructor
        // (#48): (EventBus, ErrorHandler, ExecutorService, boolean
        // publishLifecycleEvents, Duration shutdownTimeout, TikoOptions options).
        // Per-module containers see a non-null executor so their internal ownsEventExecutor
        // becomes false — only the aggregator shuts the executor down. The shutdownTimeout
        // is forwarded so per-module containers built outside this aggregator path still
        // honour the configured drain window. The TikoOptions reference lets generated
        // getters consult test/runtime overrides via this field.
        Class<?> containerClass = Class.forName(implClassName, true, classLoader);
        Constructor<?> constructor = containerClass.getDeclaredConstructor(Tiko.CONTAINER_CTOR_PARAM_TYPES);
        Container moduleContainer = (Container) constructor.newInstance(
                sharedEventBus,
                errorHandler,
                eventExecutor,
                /* publishLifecycleEvents */ false,
                shutdownTimeout,
                options);

        moduleContainers.add(moduleContainer);
        containersByImplName.put(implClassName, moduleContainer);

        // Each module container brings its own EventRegistry_<hash>; register handlers
        // on the shared bus so cross-module @EventHandler subscriptions wire up regardless
        // of which container hosts the producer or consumer.
        Tiko.registerEventHandlers(sharedEventBus, moduleContainer, containerClass);

        loadComponentsMapping(resourceUrl, classLoader, moduleContainer);
        loadConfigsMapping(resourceUrl, classLoader, moduleContainer);
    }

    /**
     * Reads the components.txt sibling of {@code resourceUrl} and maps each FQN to the given module container.
     */
    private void loadComponentsMapping(URL resourceUrl, ClassLoader classLoader, Container moduleContainer)
            throws Exception {
        var componentsUrl = siblingResource(resourceUrl, "components.txt");
        try (var reader = new BufferedReader(new InputStreamReader(componentsUrl.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    var componentClass = Class.forName(line, false, classLoader);
                    componentToContainerMap.put(componentClass, moduleContainer);
                }
            }
        }
    }

    /**
     * Reads the configs.txt sibling of {@code resourceUrl} (one entry per {@code @Configuration} record) and
     * maps each FQN to the given module container. Missing configs.txt is treated as "no configs for this
     * module" and silently ignored.
     */
    private void loadConfigsMapping(URL resourceUrl, ClassLoader classLoader, Container moduleContainer) {
        var configsUrl = siblingResource(resourceUrl, "configs.txt");
        try (var reader = new BufferedReader(new InputStreamReader(configsUrl.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                var eq = line.indexOf('=');
                if (eq > 0) {
                    var fqn = line.substring(0, eq).trim();
                    try {
                        var typeClass = Class.forName(fqn, false, classLoader);
                        configToContainer.put(typeClass, moduleContainer);
                    } catch (ClassNotFoundException e) {
                        // Module declared a config record class that's not on the classpath — surface a clear failure.
                        throw new ContainerInitializationException(
                                "Configuration record " + fqn + " referenced in configs.txt is not on the classpath",
                                e);
                    }
                }
            }
        } catch (java.io.IOException ignored) {
            // No configs.txt for this module — fine, this module has no @Configuration records.
        }
    }

    private URL siblingResource(URL base, String sibling) {
        // The base URL ends with the descriptor file name (e.g. "container.properties" or
        // "test-container.properties"); replace just that trailing segment so siblings
        // (components.txt, configs.txt) resolve regardless of which descriptor is active.
        String basePath = base.getPath();
        int lastSlash = basePath.lastIndexOf('/');
        String siblingPath = (lastSlash >= 0 ? basePath.substring(0, lastSlash + 1) : "") + sibling;
        try {
            return new URL(base.getProtocol(), base.getHost(), base.getPort(), siblingPath);
        } catch (java.net.MalformedURLException e) {
            throw new ContainerInitializationException("Failed to derive " + sibling + " URL from " + base, e);
        }
    }

    @Override
    public <T> T get(Class<T> type) {
        Container ccfg = configToContainer.get(type);
        if (ccfg != null) return ccfg.get(type);
        Container container = componentToContainerMap.get(type);
        if (container == null) {
            throw new NoSuchComponentException(type);
        }
        return container.get(type);
    }

    @Override
    public <T> T get(Class<T> type, String name) {
        // Try each container until one succeeds
        for (Container container : moduleContainers) {
            try {
                return container.get(type, name);
            } catch (NoSuchComponentException e) {
                // Try next container
            }
        }
        throw new NoSuchComponentException(type, name);
    }

    @Override
    public <T> java.util.List<T> getAll(Class<T> type) {
        // Concatenate getAll() across every sub-container so Picker<T>.list() sees
        // the union of all modules' impls. Sub-container order is the module load
        // order (deterministic per Tiko.create() classpath scan); within each module,
        // declaration order. Empty when no impls anywhere — never null.
        java.util.List<T> all = new java.util.ArrayList<>();
        for (Container container : moduleContainers) {
            all.addAll(container.getAll(type));
        }
        return java.util.Collections.unmodifiableList(all);
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type) {
        Container container = componentToContainerMap.get(type);
        if (container == null) {
            throw new NoSuchComponentException(type);
        }
        return container.getProvider(type);
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type, String name) {
        // Try each container until one succeeds
        for (Container container : moduleContainers) {
            try {
                return container.getProvider(type, name);
            } catch (UnsupportedOperationException | NoSuchComponentException e) {
                // Try next container
            }
        }
        throw new NoSuchComponentException(type, name);
    }

    @Override
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    @Override
    public EventBus getEventBus() {
        return sharedEventBus;
    }

    @Override
    public java.util.concurrent.ExecutorService getEventExecutor() {
        // Returns the shared executor (#51): same instance across all per-module containers.
        return eventExecutor;
    }

    @Override
    public void runInRequestScope(Runnable task) {
        // The task represents one request; it must run exactly once. Each module container
        // still gets its own scope frame (so REQUEST-scoped beans isolate per module) — we
        // achieve this by nesting the scope helpers and running the task in the innermost.
        runNested(moduleContainers.iterator(), task, Container::runInRequestScope);
    }

    @Override
    public <T> T supplyInRequestScope(Supplier<T> supplier) {
        return supplyNested(moduleContainers.iterator(), supplier, Container::supplyInRequestScope);
    }

    @Override
    public void runInEventScope(Runnable task) {
        runNested(moduleContainers.iterator(), task, Container::runInEventScope);
    }

    @Override
    public <T> T supplyInEventScope(Supplier<T> supplier) {
        return supplyNested(moduleContainers.iterator(), supplier, Container::supplyInEventScope);
    }

    /**
     * Recursively nests {@code scopeHelper} calls across {@code containers}, ensuring
     * {@code task} is invoked exactly once inside the innermost scope frame. Each container
     * still sees the task running within its own scope, so REQUEST/EVENT-scoped beans
     * remain isolated per module while cross-module side effects (event publishes, etc.)
     * fire only once.
     */
    private static void runNested(
            java.util.Iterator<Container> containers,
            Runnable task,
            java.util.function.BiConsumer<Container, Runnable> scopeHelper) {
        if (!containers.hasNext()) {
            task.run();
            return;
        }
        Container next = containers.next();
        scopeHelper.accept(next, () -> runNested(containers, task, scopeHelper));
    }

    /**
     * Supplier counterpart of {@link #runNested}: the inner supplier yields the result; outer
     * scopes only set up / tear down their own frames.
     */
    private static <T> T supplyNested(
            java.util.Iterator<Container> containers,
            Supplier<T> supplier,
            java.util.function.BiFunction<Container, Supplier<T>, T> scopeHelper) {
        if (!containers.hasNext()) {
            return supplier.get();
        }
        Container next = containers.next();
        return scopeHelper.apply(next, () -> supplyNested(containers, supplier, scopeHelper));
    }

    @Override
    public void start() {
        // Idempotency CAS (#45): start() is also reachable from user code now that it's
        // on the Container interface; double-call is a no-op.
        if (!startInvoked.compareAndSet(false, true)) {
            return;
        }
        // Multi-module is intentionally lazy — we do NOT call start() on per-module
        // containers here. Singletons construct on first get(). Eager-init opt-in is
        // tracked separately as #46.
        this.startedAt = Instant.now();
        try {
            sharedEventBus.publish(new ApplicationStartedEvent(this.startedAt));
        } catch (Throwable t) {
            // Bus-impl defect; user-facing flow must continue. Handler exceptions are
            // already isolated by #44, so this catch fires only for genuine bus bugs.
            System.getLogger("io.tiko.events")
                    .log(System.Logger.Level.WARNING, "ApplicationStartedEvent publish threw", t);
        }
    }

    @Override
    public void shutdown() {
        // Idempotency CAS (#47): per-module containers are independently idempotent now,
        // but guarding here avoids re-walking the list on duplicate calls.
        if (!shutdownInvoked.compareAndSet(false, true)) {
            return;
        }
        // Publish ApplicationEndingEvent ONCE on the shared bus before delegating to
        // per-module shutdowns (#45). Per-module containers were constructed with
        // publishLifecycleEvents=false, so they will not publish their own.
        Instant endTimestamp = Instant.now();
        Duration uptime = (this.startedAt != null) ? Duration.between(this.startedAt, endTimestamp) : Duration.ZERO;
        try {
            sharedEventBus.publish(new ApplicationEndingEvent(endTimestamp, uptime));
        } catch (Throwable t) {
            // Bus-impl defect; per-module @PreDestroy must still run.
            System.getLogger("io.tiko.events")
                    .log(System.Logger.Level.WARNING, "ApplicationEndingEvent publish threw", t);
        }
        // Shutdown in reverse order. Per-module containers no longer shut down the executor
        // themselves (#51): they were constructed with the shared executor, so their internal
        // ownsEventExecutor is false. The aggregator owns the lifecycle below.
        for (int i = moduleContainers.size() - 1; i >= 0; i--) {
            try {
                moduleContainers.get(i).shutdown();
            } catch (Exception e) {
                // Log but continue shutting down other containers
                System.getLogger("io.tiko.events")
                        .log(System.Logger.Level.WARNING, "Error shutting down module container", e);
            }
        }
        // Shut down framework-owned event executor (#51). User-supplied executors are not
        // touched — the user owns their lifecycle. Mirrors the per-module shutdown logic
        // moved up to the aggregator level.
        if (ownsEventExecutor) {
            eventExecutor.shutdown();
            try {
                if (!eventExecutor.awaitTermination(
                        shutdownTimeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    eventExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                eventExecutor.shutdownNow();
            }
        }
    }

    /**
     * Distributes bound configuration records to the module containers that own them.
     *
     * @param configs map from config record class to bound instance
     * @throws IllegalStateException if a config type is not owned by any known module
     */
    public void injectConfigs(java.util.Map<Class<?>, Object> configs) {
        for (java.util.Map.Entry<Class<?>, Object> e : configs.entrySet()) {
            Container target = configToContainer.get(e.getKey());
            if (target == null) {
                throw new ContainerInitializationException("No module owns config type "
                        + e.getKey().getName() + ". Discovered config types: " + configToContainer.keySet());
            }
            try {
                target.getClass()
                        .getMethod("injectConfigs", java.util.Map.class)
                        .invoke(target, java.util.Map.of(e.getKey(), e.getValue()));
            } catch (Exception ex) {
                throw new ContainerInitializationException(
                        "Failed to inject config " + e.getKey().getName(), ex);
            }
        }
    }

    /**
     * Returns the number of module containers discovered.
     * Useful for testing and diagnostics.
     */
    public int getModuleCount() {
        return moduleContainers.size();
    }
}
