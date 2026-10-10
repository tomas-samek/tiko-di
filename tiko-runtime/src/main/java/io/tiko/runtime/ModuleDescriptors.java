package io.tiko.runtime;

import io.tiko.ContainerInitializationException;
import io.tiko.TikoModule;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds each module's container descriptor (#537). Main descriptors come from every
 * {@link TikoModule} listed in {@code META-INF/services} — which a shaded fat jar merges — and
 * then from the legacy fixed path {@code META-INF/tiko/container.properties} (modules compiled
 * by an older {@code tiko-processor}, of which a fat jar keeps only one copy). One descriptor per
 * container implementation; the per-module one wins, so its sibling {@code components.txt} and
 * {@code configs.txt} are that module's own.
 */
final class ModuleDescriptors {

    private ModuleDescriptors() {}

    /** The descriptors for {@code descriptorName}: module-discovered for the main descriptor, plain lookup otherwise. */
    static List<URL> find(String descriptorName, ClassLoader cl) throws IOException {
        if (!AggregatingContainer.DEFAULT_DESCRIPTOR.equals(descriptorName)) {
            return Collections.list(cl.getResources(descriptorName));
        }
        Map<String, URL> byImpl = new LinkedHashMap<>();
        for (URL url : perModule(cl)) {
            byImpl.putIfAbsent(implOf(url), url);
        }
        for (URL url : Collections.list(cl.getResources(descriptorName))) {
            byImpl.putIfAbsent(implOf(url), url);
        }
        return List.copyOf(byImpl.values());
    }

    private static List<URL> perModule(ClassLoader cl) {
        try {
            return ServiceLoader.load(TikoModule.class, cl).stream()
                    .map(module -> cl.getResource(module.get().descriptorRoot() + "container.properties"))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (ServiceConfigurationError e) {
            throw new ContainerInitializationException(
                    "Could not load a Tiko module listed in META-INF/services/" + TikoModule.class.getName()
                            + ". Rebuild the module that lists it with tiko-processor.",
                    e);
        }
    }

    /** The descriptor's container class, or the URL itself when unreadable (the caller then reports it). */
    private static String implOf(URL descriptor) throws IOException {
        Properties props = new Properties();
        try (var in = descriptor.openStream()) {
            props.load(in);
        }
        String impl = props.getProperty("impl");
        return impl == null || impl.isBlank() ? descriptor.toString() : impl.trim();
    }
}
