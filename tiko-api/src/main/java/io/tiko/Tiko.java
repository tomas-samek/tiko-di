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
     * Creates a new container instance.
     * <p>
     * Automatically detects single-module vs multi-module scenarios:
     * <ul>
     *   <li>Single module: Direct instantiation of generated container</li>
     *   <li>Multiple modules: Uses AggregatingContainer to coordinate across modules</li>
     * </ul>
     * <p>
     * Fails fast if {@code @Configuration} records are declared but no {@link ConfigSource}
     * was provided. Use {@link #create(ConfigSource)} in that case.
     *
     * @return a new container instance
     * @throws IllegalStateException if the generated container class cannot be found or instantiated,
     *                               or if {@code @Configuration} records are declared without a source
     */
    public static Container create() {
        failIfConfigsMissingSource();
        return createInternal(null);
    }

    /**
     * Creates a new container instance with the given configuration source.
     * The source is loaded, interpolated, and bound to all declared {@code @Configuration}
     * records before the container is started.
     *
     * @param source the configuration source, never {@code null}
     * @return a new container instance
     * @throws IllegalStateException if the generated container class cannot be found or instantiated
     */
    public static Container create(ConfigSource source) {
        return createInternal(java.util.Objects.requireNonNull(source, "source"));
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

    private static Container createInternal(ConfigSource source) {
        try {
            // 1. Create EventBus instance
            Class<?> eventBusClass = Class.forName("io.tiko.event.local.LocalEventBus");
            EventBus eventBus = (EventBus) eventBusClass.getDeclaredConstructor().newInstance();

            // 2. Detect single vs multi-module scenario
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) classLoader = Tiko.class.getClassLoader();

            var resources = classLoader.getResources("META-INF/tiko/container.properties");
            int moduleCount = 0;
            while (resources.hasMoreElements()) { resources.nextElement(); moduleCount++; }

            Container container;
            if (moduleCount > 1) {
                // Multi-module: Use AggregatingContainer
                Class<?> aggregatingClass = Class.forName("io.tiko.runtime.AggregatingContainer");
                container = (Container) aggregatingClass
                    .getDeclaredConstructor(EventBus.class)
                    .newInstance(eventBus);
            } else {
                // Single module: Direct instantiation (does NOT call start yet)
                container = createSingleModuleContainer(eventBus);
            }

            // 3. Inject config singletons before start(), so @PostConstruct can use them
            if (source != null) {
                java.util.Map<Class<?>, Object> bound = bindConfigs(source, classLoader);
                container.getClass().getMethod("injectConfigs", java.util.Map.class).invoke(container, bound);
            }

            // 4. Start the container (initialize all SINGLETON components)
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
    private static Container createSingleModuleContainer(EventBus eventBus) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = Tiko.class.getClassLoader();

        var resources = classLoader.getResources("META-INF/tiko/container.properties");
        if (resources.hasMoreElements()) {
            var props = new java.util.Properties();
            try (var input = resources.nextElement().openStream()) {
                props.load(input);
            }
            String implClassName = props.getProperty("impl");
            Class<?> implClass = Class.forName(implClassName);
            Container container = (Container) implClass.getDeclaredConstructor(EventBus.class).newInstance(eventBus);

            registerEventHandlers(eventBus, container, implClass);

            // NOTE: do NOT call start() here — createInternal calls it AFTER injectConfigs
            return container;
        } else {
            // Fallback to the old hardcoded class name for backward compatibility
            Class<?> implClass = Class.forName("io.tiko.generated.TikoContainerImpl");
            Container container = (Container) implClass.getDeclaredConstructor(EventBus.class).newInstance(eventBus);

            registerEventHandlers(eventBus, container, implClass);

            // NOTE: do NOT call start() here — createInternal calls it AFTER injectConfigs
            return container;
        }
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
