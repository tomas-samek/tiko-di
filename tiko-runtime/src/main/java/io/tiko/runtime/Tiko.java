package io.tiko.runtime;

import io.tiko.ConfigSource;
import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;

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
            while (resources.hasMoreElements()) { resources.nextElement(); moduleCount++; }

            Container container;
            if (moduleCount > 1) {
                container = new AggregatingContainer(eventBus, errorHandler, options.eventExecutor());
            } else {
                // Single module: Direct instantiation (does NOT call start yet)
                container = createSingleModuleContainer(eventBus, errorHandler, options.eventExecutor());
            }

            // 4. Inject config singletons before start(), so @PostConstruct can use them.
            // Defaults from META-INF/tiko/defaults.yaml are always layered under the user
            // source — modules can ship a self-sufficient bean even when the user provides
            // no ConfigSource. bindConfigs is a no-op when no @Configuration records exist.
            Map<Class<?>, Object> bound = bindConfigs(options.configSource(), classLoader);
            if (!bound.isEmpty()) {
                container.getClass().getMethod("injectConfigs", Map.class).invoke(container, bound);
            }

            // 5. Start the container — single-module's TikoContainerImpl.start() initialises
            // all SINGLETON components and publishes ApplicationStartedEvent;
            // multi-module's AggregatingContainer.start() publishes ApplicationStartedEvent
            // once on the shared bus and leaves per-module singleton init lazy (#45).
            container.start();

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
     *
     * <p>Layers module-baked {@code META-INF/tiko/defaults.yaml} under the (optional)
     * user source so each module can ship its own private slice of defaults inside
     * its jar — overrideable per-key by the user file (#18).</p>
     *
     * <p>Returns an empty map if no {@code @Configuration} records are declared on
     * the classpath. Uses reflection to avoid a circular compile dependency on
     * tiko-config.</p>
     */
    private static Map<Class<?>, Object> bindConfigs(ConfigSource userSource, ClassLoader cl) throws Exception {
        List<Object> binders = new ArrayList<>();
        var resources = cl.getResources("META-INF/tiko/configs.txt");
        while (resources.hasMoreElements()) {
            var url = resources.nextElement();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    url.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("# registry=")) {
                        String registryFqn = line.substring("# registry=".length()).trim();
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
        ConfigSource defaults = (ConfigSource) sourcesClass
            .getMethod("classpathAll", String.class)
            .invoke(null, "META-INF/tiko/defaults.yaml");
        ConfigSource effective;
        if (userSource == null) {
            effective = defaults;
        } else {
            effective = (ConfigSource) sourcesClass
                .getMethod("layered", ConfigSource[].class)
                .invoke(null, (Object) new ConfigSource[]{ defaults, userSource });
        }

        // Delegate to ConfigBootstrap via reflection (avoids circular compile dep).
        Class<?> bootstrapClass = Class.forName("io.tiko.config.runtime.ConfigBootstrap", true, cl);
        @SuppressWarnings("unchecked")
        Map<Class<?>, Object> result = (Map<Class<?>, Object>)
            bootstrapClass.getMethod("bind", String.class, ConfigSource.class, List.class)
                .invoke(null, "config", effective, binders);
        return result;
    }

    /**
     * Creates a single-module container. Does NOT call start() — that is done in createInternal
     * after injectConfigs() runs.
     */
    private static Container createSingleModuleContainer(
            EventBus eventBus, ErrorHandler errorHandler,
            ExecutorService userEventExecutor) throws Exception {
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
            .getDeclaredConstructor(EventBus.class, ErrorHandler.class,
                ExecutorService.class, boolean.class)
            .newInstance(eventBus, errorHandler, userEventExecutor, /* publishLifecycleEvents */ true);

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
