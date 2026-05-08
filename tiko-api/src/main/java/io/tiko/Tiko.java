package io.tiko;

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
            .configSource(java.util.Objects.requireNonNull(source, "source"))
            .build());
    }

    /**
     * Creates a container with the supplied options.
     *
     * @param options framework knobs (config source, error handler, ...). Never {@code null}.
     */
    public static Container create(TikoOptions options) {
        java.util.Objects.requireNonNull(options, "options");
        if (options.configSource() == null) {
            failIfConfigsMissingSource();
        }
        return createInternal(options);
    }

    /**
     * Checks the classpath for META-INF/tiko/configs.txt. If any config records are declared
     * there, throws IllegalStateException telling the user to use Tiko.create(ConfigSource).
     */
    private static void failIfConfigsMissingSource() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = Tiko.class.getClassLoader();
            var resources = cl.getResources("META-INF/tiko/configs.txt");
            java.util.List<String> declared = new java.util.ArrayList<>();
            while (resources.hasMoreElements()) {
                java.net.URL url = resources.nextElement();
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int eq = line.indexOf('=');
                        if (eq > 0) declared.add(line.substring(0, eq));
                    }
                }
            }
            if (!declared.isEmpty()) {
                throw new IllegalStateException(
                    "You declared @Configuration records (" + String.join(", ", declared)
                        + ") but called Tiko.create() without a ConfigSource. "
                        + "Use Tiko.create(ConfigSources.classpath(\"config.yaml\")) or similar.");
            }
        } catch (java.io.IOException ignored) { /* no manifest — no configs declared */ }
    }

    private static Container createInternal(TikoOptions options) {
        try {
            // 1. Resolve the ErrorHandler — user-supplied or the JUL-backed DefaultErrorHandler.
            ErrorHandler errorHandler = options.errorHandler();
            if (errorHandler == null) {
                errorHandler = resolveDefaultErrorHandler();
            }

            // 2. Create EventBus instance (still no-arg; the bus does not take ErrorHandler).
            Class<?> eventBusClass = Class.forName("io.tiko.event.local.LocalEventBus");
            EventBus eventBus = (EventBus) eventBusClass.getDeclaredConstructor().newInstance();

            // 3. Detect single vs multi-module scenario
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) classLoader = Tiko.class.getClassLoader();

            var resources = classLoader.getResources("META-INF/tiko/container.properties");
            int moduleCount = 0;
            while (resources.hasMoreElements()) { resources.nextElement(); moduleCount++; }

            Container container;
            if (moduleCount > 1) {
                // Multi-module: AggregatingContainer — try 3-arg constructor first,
                // fall back to 2-arg, then legacy 1-arg (multi-module executor wiring is
                // out of scope; each per-module container builds its own default executor).
                Class<?> aggregatingClass = Class.forName("io.tiko.runtime.AggregatingContainer");
                try {
                    container = (Container) aggregatingClass
                        .getDeclaredConstructor(EventBus.class, ErrorHandler.class, java.util.concurrent.ExecutorService.class)
                        .newInstance(eventBus, errorHandler, options.eventExecutor());
                } catch (NoSuchMethodException nsm3) {
                    try {
                        container = (Container) aggregatingClass
                            .getDeclaredConstructor(EventBus.class, ErrorHandler.class)
                            .newInstance(eventBus, errorHandler);
                    } catch (NoSuchMethodException nsm2) {
                        container = (Container) aggregatingClass
                            .getDeclaredConstructor(EventBus.class)
                            .newInstance(eventBus);
                    }
                }
            } else {
                // Single module: Direct instantiation (does NOT call start yet)
                container = createSingleModuleContainer(eventBus, errorHandler, options.eventExecutor());
            }

            // 4. Inject config singletons before start(), so @PostConstruct can use them
            if (options.configSource() != null) {
                java.util.Map<Class<?>, Object> bound = bindConfigs(options.configSource(), classLoader);
                container.getClass().getMethod("injectConfigs", java.util.Map.class).invoke(container, bound);
            }

            // 5. Start the container (initialize all SINGLETON components)
            if (moduleCount <= 1) {
                container.getClass().getMethod("start").invoke(container);
            }
            // Multi-module: per-module containers are constructed lazily by the aggregator;
            // their `start()` is not invoked eagerly here. Singletons initialise on first get().
            // (This is pre-existing behaviour from the multi-module work, not introduced by config injection.)

            return container;
        } catch (RuntimeException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "Tiko container implementation not found. Did you include tiko-processor in your annotation processor path?", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create container instance", e);
        }
    }

    /**
     * Reflectively builds the JUL-backed {@code DefaultErrorHandler} from {@code tiko-event-local}.
     * Kept reflective so {@code tiko-api} stays free of any concrete handler dependency.
     */
    private static ErrorHandler resolveDefaultErrorHandler() {
        try {
            Class<?> defaultClass = Class.forName("io.tiko.event.local.DefaultErrorHandler");
            java.lang.reflect.Constructor<?> ctor = defaultClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (ErrorHandler) ctor.newInstance();
        } catch (ClassNotFoundException e) {
            // Bus implementation not on the classpath — return a minimal no-op so we do not
            // crash users who have somehow excluded tiko-event-local. This will also be
            // surfaced when EventBus construction fails downstream.
            return ctx -> {};
        } catch (Exception e) {
            throw new IllegalStateException("Failed to construct default ErrorHandler", e);
        }
    }

    /**
     * Loads and binds all declared @Configuration records from configs.txt manifests.
     * Uses full reflection to avoid a circular compile dependency on tiko-config.
     */
    private static java.util.Map<Class<?>, Object> bindConfigs(ConfigSource source, ClassLoader cl) throws Exception {
        java.util.List<Object> binders = new java.util.ArrayList<>();
        var resources = cl.getResources("META-INF/tiko/configs.txt");
        while (resources.hasMoreElements()) {
            java.net.URL url = resources.nextElement();
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("# registry=")) {
                        String registryFqn = line.substring("# registry=".length()).trim();
                        Class<?> registryClass = Class.forName(registryFqn, true, cl);
                        @SuppressWarnings("unchecked")
                        java.util.List<Object> moduleBinders =
                            (java.util.List<Object>) registryClass.getMethod("all").invoke(null);
                        binders.addAll(moduleBinders);
                        break;
                    }
                }
            }
        }
        // Delegate to ConfigBootstrap via reflection to avoid circular dependency
        Class<?> bootstrapClass = Class.forName("io.tiko.config.runtime.ConfigBootstrap", true, cl);
        // ConfigBootstrap.bind(String, ConfigSource, List<ConfigBinder<?>>)
        // We use the raw List type reflectively
        @SuppressWarnings("unchecked")
        java.util.Map<Class<?>, Object> result = (java.util.Map<Class<?>, Object>)
            bootstrapClass.getMethod("bind", String.class, ConfigSource.class, java.util.List.class)
                .invoke(null, "config", source, binders);
        return result;
    }

    /**
     * Creates a single-module container. Does NOT call start() — that is done in createInternal
     * after injectConfigs() runs.
     */
    private static Container createSingleModuleContainer(
            EventBus eventBus, ErrorHandler errorHandler,
            java.util.concurrent.ExecutorService userEventExecutor) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = Tiko.class.getClassLoader();

        var resources = classLoader.getResources("META-INF/tiko/container.properties");
        Class<?> implClass;
        if (resources.hasMoreElements()) {
            var props = new java.util.Properties();
            try (var input = resources.nextElement().openStream()) {
                props.load(input);
            }
            String implClassName = props.getProperty("impl");
            implClass = Class.forName(implClassName);
        } else {
            implClass = Class.forName("io.tiko.generated.TikoContainerImpl");
        }

        Container container = (Container) implClass
            .getDeclaredConstructor(EventBus.class, ErrorHandler.class, java.util.concurrent.ExecutorService.class)
            .newInstance(eventBus, errorHandler, userEventExecutor);

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
}
