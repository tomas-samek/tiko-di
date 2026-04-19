package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.Provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    private final EventBus sharedEventBus;
    private final List<Container> moduleContainers;
    private final Map<Class<?>, Container> componentToContainerMap;

    /**
     * Creates an aggregating container by discovering all module containers on the classpath.
     *
     * @param eventBus shared event bus instance passed to all module containers
     * @throws IllegalStateException if container discovery or initialization fails
     */
    public AggregatingContainer(EventBus eventBus) {
        this.sharedEventBus = eventBus;
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
            throw new IllegalStateException(
                "Missing 'impl' property in " + resourceUrl);
        }

        // Load and instantiate the container
        Class<?> containerClass = Class.forName(implClassName, true, classLoader);
        Constructor<?> constructor = containerClass.getDeclaredConstructor(EventBus.class);
        Container moduleContainer = (Container) constructor.newInstance(sharedEventBus);

        moduleContainers.add(moduleContainer);

        // Load components mapping
        String resourcePath = resourceUrl.getPath();
        String componentsPath = resourcePath.replace("container.properties", "components.txt");
        URL componentsUrl = new URL(resourceUrl.getProtocol(), resourceUrl.getHost(),
            resourceUrl.getPort(), componentsPath);

        try (var reader = new BufferedReader(new InputStreamReader(componentsUrl.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    Class<?> componentClass = Class.forName(line, false, classLoader);
                    componentToContainerMap.put(componentClass, moduleContainer);
                }
            }
        }
    }

    @Override
    public <T> T get(Class<T> type) {
        Container container = componentToContainerMap.get(type);
        if (container == null) {
            throw new IllegalArgumentException(
                "No component found for type: " + type.getName() +
                ". Available components: " + componentToContainerMap.keySet());
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
        throw new IllegalArgumentException(
            "No component found for type: " + type.getName() + " with name: " + name);
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
        throw new IllegalArgumentException(
            "No component found for type: " + type.getName() + " with name: " + name);
    }

    @Override
    public EventBus getEventBus() {
        return sharedEventBus;
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
    public void shutdown() {
        // Shutdown in reverse order
        for (int i = moduleContainers.size() - 1; i >= 0; i--) {
            try {
                moduleContainers.get(i).shutdown();
            } catch (Exception e) {
                // Log but continue shutting down other containers
                System.err.println("Error shutting down module container: " + e.getMessage());
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
