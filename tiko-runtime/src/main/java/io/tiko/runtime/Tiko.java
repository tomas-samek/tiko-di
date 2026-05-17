package io.tiko.runtime;

import io.tiko.ConfigSource;
import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.TransportBootstrap;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

    private static Container createInternal(TikoOptions options) {
        try {
            // 1. Resolve the ErrorHandler — user-supplied or the JUL-backed DefaultErrorHandler.
            ErrorHandler errorHandler = options.errorHandler();
            if (errorHandler == null) {
                errorHandler = new DefaultErrorHandler();
            }

            // 2. Create EventBus instance (still no-arg; the bus does not take ErrorHandler).
            EventBus eventBus = new LocalEventBus();

            // 3. Detect single vs multi-module scenario
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) classLoader = Tiko.class.getClassLoader();

            var resources = classLoader.getResources("META-INF/tiko/container.properties");
            int moduleCount = 0;
            while (resources.hasMoreElements()) {
                resources.nextElement();
                moduleCount++;
            }

            java.time.Duration effectiveShutdownTimeout = resolveShutdownTimeout(options, classLoader);

            Container container;
            if (moduleCount > 1) {
                container = new AggregatingContainer(
                        eventBus, errorHandler, options.eventExecutor(), effectiveShutdownTimeout);
            } else {
                // Single module: Direct instantiation (does NOT call start yet)
                container = createSingleModuleContainer(
                        eventBus, errorHandler, options.eventExecutor(), effectiveShutdownTimeout);
            }

            // 4. Inject config singletons before start(), so @PostConstruct can use them.
            // Defaults from META-INF/tiko/defaults.yaml are always layered under the user
            // source — modules can ship a self-sufficient bean even when the user provides
            // no ConfigSource. bindConfigs is a no-op when no @Configuration records exist.
            Map<Class<?>, Object> bound = bindConfigs(options.configSource(), classLoader, errorHandler);
            if (!bound.isEmpty()) {
                container.getClass().getMethod("injectConfigs", Map.class).invoke(container, bound);
            }

            // 5. Start the container — single-module's TikoContainerImpl.start() initialises
            // all SINGLETON components and publishes ApplicationStartedEvent;
            // multi-module's AggregatingContainer.start() publishes ApplicationStartedEvent
            // once on the shared bus and leaves per-module singleton init lazy (#45).
            container.start();

            // 6. Discover transport modules (tiko-kafka, future tiko-http, ...). Each transport
            //    ships its own ServiceLoader entry; the runtime knows nothing transport-specific.
            //    Bootstraps are collected first so the wrapper can be built before start() is
            //    called — that way start(container) receives the public wrapper, not the raw impl.
            java.util.List<TransportBootstrap> bootstraps = new java.util.ArrayList<>();
            for (TransportBootstrap tb : java.util.ServiceLoader.load(TransportBootstrap.class, classLoader)) {
                bootstraps.add(tb);
            }

            if (bootstraps.isEmpty()) {
                return container;
            }

            // Build the wrapper first so start() callers receive the public-facing handle.
            TransportAwareContainer wrapper = new TransportAwareContainer(container, bootstraps);
            for (TransportBootstrap tb : bootstraps) {
                tb.start(wrapper);
            }
            return wrapper;
        } catch (RuntimeException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Tiko container implementation not found. Did you include tiko-processor in your annotation processor path?",
                    e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create container instance", e);
        }
    }

    /**
     * Loads and binds all declared @Configuration records from configs.txt manifests.
     *
     * <p>Layers module-baked {@code META-INF/tiko/defaults.yaml} under the (optional)
     * user source so each module can ship its own private slice of defaults inside
     * its jar — overrideable per-key by the user file (#18).</p>
     *
     * <p>Returns an empty map if no {@code @Configuration} records are declared on
     * the classpath. Uses reflection to avoid a circular compile dependency on
     * tiko-config.</p>
     */
    private static Map<Class<?>, Object> bindConfigs(ConfigSource userSource, ClassLoader cl, ErrorHandler errorHandler)
            throws Exception {
        List<Object> binders = new ArrayList<>();
        var resources = cl.getResources("META-INF/tiko/configs.txt");
        while (resources.hasMoreElements()) {
            var url = resources.nextElement();
            try (BufferedReader br =
                    new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("# registry=")) {
                        String registryFqn =
                                line.substring("# registry=".length()).trim();
                        Class<?> registryClass = Class.forName(registryFqn, true, cl);
                        @SuppressWarnings("unchecked")
                        List<Object> moduleBinders =
                                (List<Object>) registryClass.getMethod("all").invoke(null);
                        binders.addAll(moduleBinders);
                        break;
                    }
                }
            }
        }

        // Nothing declared — skip the reflective ConfigBootstrap call entirely.
        if (binders.isEmpty()) return Collections.emptyMap();

        // Build the effective ConfigSource: defaults first, user override on top.
        Class<?> sourcesClass = Class.forName("io.tiko.config.ConfigSources", true, cl);
        ConfigSource defaults = (ConfigSource)
                sourcesClass.getMethod("classpathAll", String.class).invoke(null, "META-INF/tiko/defaults.yaml");
        ConfigSource effective;
        if (userSource == null) {
            effective = defaults;
        } else {
            effective = (ConfigSource)
                    sourcesClass.getMethod("layered", ConfigSource[].class).invoke(null, (Object)
                            new ConfigSource[] {defaults, userSource});
        }

        // Delegate to ConfigBootstrap via reflection (avoids a tiko-runtime → tiko-config
        // compile dependency). ConfigBootstrap.bind itself routes a single
        // ConfigurationFailure through the supplied ErrorHandler before throwing, so this
        // call site doesn't need to unwrap reflective exceptions or detect specific cause
        // types — it just surfaces whatever the underlying call threw.
        Class<?> bootstrapClass = Class.forName("io.tiko.config.runtime.ConfigBootstrap", true, cl);
        try {
            @SuppressWarnings("unchecked")
            Map<Class<?>, Object> result = (Map<Class<?>, Object>) bootstrapClass
                    .getMethod("bind", String.class, ConfigSource.class, List.class, ErrorHandler.class)
                    .invoke(null, "config", effective, binders, errorHandler);
            return result;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            // Method.invoke always wraps the called method's exception inside an
            // InvocationTargetException. We have to peel that wrap so callers of
            // Tiko.create() see ConfigValidationException directly (the type they expect
            // to catch), not the reflective wrap. Three branches are required because
            // `throw` needs a statically-typed throwable — Throwable is too broad and
            // would force a `throws` clause on this method:
            //
            //   - RuntimeException: the common case (ConfigValidationException lands here).
            //   - Error: should propagate unwrapped too; an OOM from bind shouldn't morph.
            //   - fallback: the language requires it but is unreachable in practice —
            //     ConfigBootstrap.bind declares no checked exceptions.
            //
            // The shape is intrinsic to using reflection here. The honest fix is to drop
            // the reflective call entirely by depending on tiko-config at compile time
            // with `<scope>provided</scope>` (lets users without @Configuration skip the
            // dep, while making the runtime call a plain static method invocation).
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw ite;
        }
    }

    /**
     * Computes the effective event-executor shutdown timeout with precedence:
     * programmatic ({@link TikoOptions#shutdownTimeout()}) > YAML ({@code tiko.shutdownTimeout}
     * in the layered config sources) > {@code Duration.ofSeconds(10)}.
     *
     * <p>Package-private for testability — {@code TikoResolveShutdownTimeoutTest} drives this
     * helper directly without booting a container.
     *
     * <p>The YAML lookup is reflective (mirrors the existing {@code bindConfigs} path) so
     * tiko-runtime does not gain a compile dep on tiko-config. If tiko-config is not on the
     * classpath, the YAML path silently falls through to the default. No {@code ${VAR}}
     * interpolation is applied on {@code tiko.shutdownTimeout} in v1.
     */
    static java.time.Duration resolveShutdownTimeout(TikoOptions options, ClassLoader classLoader) {
        java.time.Duration explicit = options.shutdownTimeout();
        if (explicit != null) {
            return explicit;
        }

        java.time.Duration fromYaml = readYamlShutdownTimeout(options.configSource(), classLoader);
        if (fromYaml != null) {
            if (fromYaml.isNegative()) {
                throw new IllegalArgumentException("tiko.shutdownTimeout must not be negative; got " + fromYaml);
            }
            return fromYaml;
        }

        return java.time.Duration.ofSeconds(10);
    }

    /**
     * Returns the value of {@code tiko.shutdownTimeout} from the layered config (defaults +
     * user source) coerced via {@code Coercers.durationCoercer()}, or {@code null} if the key
     * is absent or tiko-config is not on the classpath.
     */
    private static java.time.Duration readYamlShutdownTimeout(ConfigSource userSource, ClassLoader classLoader) {
        try {
            // Build the layered source the same way bindConfigs does — defaults first,
            // user override on top.
            Class<?> sourcesClass = Class.forName("io.tiko.config.ConfigSources", true, classLoader);
            ConfigSource defaults = (ConfigSource)
                    sourcesClass.getMethod("classpathAll", String.class).invoke(null, "META-INF/tiko/defaults.yaml");
            ConfigSource effective;
            if (userSource == null) {
                effective = defaults;
            } else {
                effective = (ConfigSource)
                        sourcesClass.getMethod("layered", ConfigSource[].class).invoke(null, (Object)
                                new ConfigSource[] {defaults, userSource});
            }

            // Read raw map, walk to tiko.shutdownTimeout.
            Map<String, Object> raw = effective.load();
            Object tikoSection = raw.get("tiko");
            if (!(tikoSection instanceof Map<?, ?> tikoMap)) {
                return null;
            }
            Object value = tikoMap.get("shutdownTimeout");
            if (value == null) {
                return null;
            }

            // Coerce via the canonical durationCoercer (handles ISO-8601 like "PT5S").
            Class<?> coercersClass = Class.forName("io.tiko.config.internal.coercers.Coercers", true, classLoader);
            Object durationCoercer = coercersClass.getMethod("durationCoercer").invoke(null);
            Class<?> coercerInterface =
                    Class.forName("io.tiko.config.internal.coercers.TypeCoercer", true, classLoader);
            Object coerced = coercerInterface.getMethod("coerce", Object.class).invoke(durationCoercer, value);
            return (java.time.Duration) coerced;
        } catch (ClassNotFoundException notOnClasspath) {
            // tiko-config absent — YAML config not available; programmatic + default still work.
            return null;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            // CoercionException or similar from the coercer — surface its message.
            Throwable cause = ite.getCause();
            throw new IllegalArgumentException(
                    "Invalid tiko.shutdownTimeout in YAML: " + (cause == null ? ite.getMessage() : cause.getMessage()),
                    cause);
        } catch (Exception e) {
            // Reflective infrastructure error (e.g. method missing). Surface as runtime to
            // avoid silently masking a wiring bug.
            throw new IllegalStateException("Failed to read tiko.shutdownTimeout from YAML", e);
        }
    }

    /**
     * Creates a single-module container. Does NOT call start() — that is done in createInternal
     * after injectConfigs() runs.
     */
    private static Container createSingleModuleContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            ExecutorService userEventExecutor,
            java.time.Duration shutdownTimeout)
            throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = Tiko.class.getClassLoader();

        var resources = classLoader.getResources("META-INF/tiko/container.properties");
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
        Container container = (Container) implClass
                .getDeclaredConstructor(
                        EventBus.class,
                        ErrorHandler.class,
                        ExecutorService.class,
                        boolean.class,
                        java.time.Duration.class)
                .newInstance(
                        eventBus, errorHandler, userEventExecutor, /* publishLifecycleEvents */ true, shutdownTimeout);

        registerEventHandlers(eventBus, container, implClass);

        // NOTE: do NOT call start() here — createInternal calls it AFTER injectConfigs
        return container;
    }

    /**
     * Registers event handlers if EventRegistry is present.
     */
    private static void registerEventHandlers(EventBus eventBus, Container container, Class<?> containerClass) {
        try {
            Class<?> registryClass = Class.forName("io.tiko.generated.EventRegistry");
            var registerMethod = registryClass.getMethod("registerHandlers", EventBus.class, containerClass);
            registerMethod.invoke(null, eventBus, container);
        } catch (ClassNotFoundException e) {
            // No event handlers registered - this is OK
        } catch (Exception e) {
            // Ignore - event registration is optional
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
        public void runInRequestScope(Runnable runnable) {
            delegate.runInRequestScope(runnable);
        }

        @Override
        public <T> T supplyInRequestScope(java.util.function.Supplier<T> s) {
            return delegate.supplyInRequestScope(s);
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
