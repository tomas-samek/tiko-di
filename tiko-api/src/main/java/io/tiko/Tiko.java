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

    private Tiko() {
        // Prevent instantiation
    }

    /**
     * Creates a new container instance.
     * <p>
     * Automatically detects single-module vs multi-module scenarios:
     * <ul>
     *   <li>Single module: Direct instantiation of generated container</li>
     *   <li>Multiple modules: Uses AggregatingContainer to coordinate across modules</li>
     * </ul>
     *
     * @return a new container instance
     * @throws IllegalStateException if the generated container class cannot be found or instantiated
     */
    public static Container create() {
        try {
            // 1. Create EventBus instance
            Class<?> eventBusClass = Class.forName("io.tiko.event.local.LocalEventBus");
            EventBus eventBus = (EventBus) eventBusClass.getDeclaredConstructor().newInstance();

            // 2. Detect single vs multi-module scenario
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = Tiko.class.getClassLoader();
            }

            var resources = classLoader.getResources("META-INF/tiko/container.properties");
            int moduleCount = 0;
            while (resources.hasMoreElements()) {
                resources.nextElement();
                moduleCount++;
            }

            Container container;

            if (moduleCount > 1) {
                // Multi-module: Use AggregatingContainer
                Class<?> aggregatingClass = Class.forName("io.tiko.runtime.AggregatingContainer");
                container = (Container) aggregatingClass
                    .getDeclaredConstructor(EventBus.class)
                    .newInstance(eventBus);
            } else {
                // Single module: Direct instantiation (backward compatible)
                container = createSingleModuleContainer(eventBus);
            }

            return container;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Tiko container implementation not found. Did you include tiko-processor in your annotation processor path?", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create container instance", e);
        }
    }

    /**
     * Creates a single-module container (backward compatible path).
     */
    private static Container createSingleModuleContainer(EventBus eventBus) throws Exception {
        // Try to find container via metadata first
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = Tiko.class.getClassLoader();
        }

        var resources = classLoader.getResources("META-INF/tiko/container.properties");
        if (resources.hasMoreElements()) {
            // Load from metadata
            var props = new java.util.Properties();
            try (var input = resources.nextElement().openStream()) {
                props.load(input);
            }
            String implClassName = props.getProperty("impl");
            Class<?> implClass = Class.forName(implClassName);
            Container container = (Container) implClass.getDeclaredConstructor(EventBus.class).newInstance(eventBus);

            // Register event handlers if present
            registerEventHandlers(eventBus, container, implClass);

            // Initialize container
            var startMethod = implClass.getMethod("start");
            startMethod.invoke(container);

            return container;
        } else {
            // Fallback to the old hardcoded class name for backward compatibility
            Class<?> implClass = Class.forName("io.tiko.generated.TikoContainerImpl");
            Container container = (Container) implClass.getDeclaredConstructor(EventBus.class).newInstance(eventBus);

            registerEventHandlers(eventBus, container, implClass);

            var startMethod = implClass.getMethod("start");
            startMethod.invoke(container);

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
