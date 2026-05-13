package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
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
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private final EventBus sharedEventBus;
    private final ErrorHandler errorHandler;
    private final java.util.concurrent.ExecutorService eventExecutor;
    private final boolean ownsEventExecutor;
    private final List<Container> moduleContainers;
    private final Map<Class<?>, Container> componentToContainerMap;
    private final Map<Class<?>, Container> configToContainer = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdownInvoked = new AtomicBoolean(false);
    private final AtomicBoolean startInvoked = new AtomicBoolean(false);
    private volatile Instant startedAt;

    /**
     * Creates an aggregating container by discovering all module containers on the classpath.
     *
     * @param eventBus shared event bus instance passed to all module containers
     * @throws IllegalStateException if container discovery or initialization fails
     */
    public AggregatingContainer(EventBus eventBus) {
        this(eventBus, ctx -> {}, null);
    }

    /**
     * Creates an aggregating container with a custom error handler.
     *
     * @param eventBus     shared event bus instance passed to all module containers
     * @param errorHandler error handler for event handler exceptions
     * @throws IllegalStateException if container discovery or initialization fails
     */
    public AggregatingContainer(EventBus eventBus, ErrorHandler errorHandler) {
        this(eventBus, errorHandler, null);
    }

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
     * @throws IllegalStateException if container discovery or initialization fails
     */
    public AggregatingContainer(
            EventBus eventBus, ErrorHandler errorHandler, java.util.concurrent.ExecutorService userEventExecutor) {
        this.sharedEventBus = eventBus;
        this.errorHandler = errorHandler;
        this.eventExecutor = userEventExecutor != null ? userEventExecutor : DefaultEventExecutorFactory.create();
        this.ownsEventExecutor = (userEventExecutor == null);
        this.moduleContainers = new ArrayList<>();
        this.componentToContainerMap = new ConcurrentHashMap<>();

        try {
            discoverAndInitializeModuleContainers();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize aggregating container", e);
        }
    }

    /**
     * Discovers all module containers by scanning META-INF/tiko/container.properties.
     */
    private void discoverAndInitializeModuleContainers() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = AggregatingContainer.class.getClassLoader();
        }

        // Find all container.properties files
        Enumeration<URL> resources = classLoader.getResources("META-INF/tiko/container.properties");

        while (resources.hasMoreElements()) {
            URL resourceUrl = resources.nextElement();
            processContainerResource(resourceUrl, classLoader);
        }

        if (moduleContainers.isEmpty()) {
            throw new IllegalStateException(
                    "No Tiko containers found on classpath. Expected at least one META-INF/tiko/container.properties file.");
        }
    }

    /**
     * Processes a single container.properties resource.
     */
    private void processContainerResource(URL resourceUrl, ClassLoader classLoader) throws Exception {
        // Read container.properties to get impl class name
        Properties props = new Properties();
        try (var input = resourceUrl.openStream()) {
            props.load(input);
        }

        String implClassName = props.getProperty("impl");
        if (implClassName == null || implClassName.trim().isEmpty()) {
            throw new IllegalStateException("Missing 'impl' property in " + resourceUrl);
        }

        // Load and instantiate the container with 4-arg constructor (#45):
        // (EventBus, ErrorHandler, ExecutorService, boolean publishLifecycleEvents).
        // - executor: the aggregator's shared executor (#51). Per-module containers see a
        //   non-null executor so their internal `ownsEventExecutor` becomes false — only
        //   the aggregator shuts it down.
        // - publishLifecycleEvents=false: aggregator publishes ApplicationStartedEvent /
        //   ApplicationEndingEvent once on the shared bus (#45).
        Class<?> containerClass = Class.forName(implClassName, true, classLoader);
        Constructor<?> constructor = containerClass.getDeclaredConstructor(
                EventBus.class, ErrorHandler.class,
                java.util.concurrent.ExecutorService.class, boolean.class);
        Container moduleContainer = (Container) constructor.newInstance(
                sharedEventBus, errorHandler, eventExecutor, /* publishLifecycleEvents */ false);

        moduleContainers.add(moduleContainer);

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
                        throw new IllegalStateException(
                                "Configuration record " + fqn + " referenced in configs.txt is not on the classpath",
                                e);
                    }
                }
            }
        } catch (java.io.IOException ignored) {
            // No configs.txt for this module — fine, this module has no @Configuration records.
        }
    }

    private static URL siblingResource(URL base, String sibling) {
        var siblingPath = base.getPath().replace("container.properties", sibling);
        try {
            return new URL(base.getProtocol(), base.getHost(), base.getPort(), siblingPath);
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("Failed to derive " + sibling + " URL from " + base, e);
        }
    }

    @Override
    public <T> T get(Class<T> type) {
        Container ccfg = configToContainer.get(type);
        if (ccfg != null) return ccfg.get(type);
        Container container = componentToContainerMap.get(type);
        if (container == null) {
            throw new IllegalArgumentException("No component found for type: " + type.getName()
                    + ". Available components: " + componentToContainerMap.keySet());
        }
        return container.get(type);
    }

    @Override
    public <T> T get(Class<T> type, String name) {
        // Try each container until one succeeds
        for (Container container : moduleContainers) {
            try {
                return container.get(type, name);
            } catch (IllegalArgumentException e) {
                // Try next container
            }
        }
        throw new IllegalArgumentException("No component found for type: " + type.getName() + " with name: " + name);
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
            throw new IllegalArgumentException("No component found for type: " + type.getName());
        }
        return container.getProvider(type);
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type, String name) {
        // Try each container until one succeeds
        for (Container container : moduleContainers) {
            try {
                return container.getProvider(type, name);
            } catch (UnsupportedOperationException | IllegalArgumentException e) {
                // Try next container
            }
        }
        throw new IllegalArgumentException("No component found for type: " + type.getName() + " with name: " + name);
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
        // Execute in all module containers
        for (Container container : moduleContainers) {
            container.runInRequestScope(task);
        }
    }

    @Override
    public <T> T supplyInRequestScope(Supplier<T> supplier) {
        // Execute in all module containers, return value from supplier
        T result = null;
        for (Container container : moduleContainers) {
            result = container.supplyInRequestScope(supplier);
        }
        return result;
    }

    @Override
    public void runInEventScope(Runnable task) {
        // Execute in all module containers
        for (Container container : moduleContainers) {
            container.runInEventScope(task);
        }
    }

    @Override
    public <T> T supplyInEventScope(Supplier<T> supplier) {
        // Execute in all module containers, return value from supplier
        T result = null;
        for (Container container : moduleContainers) {
            result = container.supplyInEventScope(supplier);
        }
        return result;
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
            Logger.getLogger("io.tiko.events").log(Level.WARNING, "ApplicationStartedEvent publish threw", t);
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
            Logger.getLogger("io.tiko.events").log(Level.WARNING, "ApplicationEndingEvent publish threw", t);
        }
        // Shutdown in reverse order. Per-module containers no longer shut down the executor
        // themselves (#51): they were constructed with the shared executor, so their internal
        // ownsEventExecutor is false. The aggregator owns the lifecycle below.
        for (int i = moduleContainers.size() - 1; i >= 0; i--) {
            try {
                moduleContainers.get(i).shutdown();
            } catch (Exception e) {
                // Log but continue shutting down other containers
                Logger.getLogger("io.tiko.events").log(Level.WARNING, "Error shutting down module container", e);
            }
        }
        // Shut down framework-owned event executor (#51). User-supplied executors are not
        // touched — the user owns their lifecycle. Mirrors the per-module shutdown logic
        // moved up to the aggregator level.
        if (ownsEventExecutor) {
            eventExecutor.shutdown();
            try {
                if (!eventExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
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
                throw new IllegalStateException("No module owns config type "
                        + e.getKey().getName() + ". Discovered config types: " + configToContainer.keySet());
            }
            try {
                target.getClass()
                        .getMethod("injectConfigs", java.util.Map.class)
                        .invoke(target, java.util.Map.of(e.getKey(), e.getValue()));
            } catch (Exception ex) {
                throw new IllegalStateException(
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
