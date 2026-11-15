package io.tiko.examples.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * #733: a single-module application boots when Tiko and the application live in different class
 * loaders: {@code tiko-api}, {@code tiko-runtime} and {@code tiko-config} in a parent loader, the
 * application's classes and generated container in a child loader set as the context class loader.
 * That is how application servers and plugin hosts load code.
 */
class IsolatedClassLoaderBootTest {

    private static URL locationOf(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    @Test
    void singleModuleApplicationBootsFromAChildClassLoader() throws Exception {
        var frameworkUrls = new URL[] {
            locationOf(Container.class), locationOf(Tiko.class), locationOf(ConfigSources.class), locationOf(Yaml.class)
        };
        var applicationUrls = new URL[] {locationOf(Main.class)};
        var original = Thread.currentThread().getContextClassLoader();
        try (var framework = new URLClassLoader(frameworkUrls, ClassLoader.getPlatformClassLoader());
                var application = new URLClassLoader(applicationUrls, framework)) {
            Thread.currentThread().setContextClassLoader(application);

            var sources = framework.loadClass(ConfigSources.class.getName());
            var configSource = sources.getMethod("classpath", String.class).invoke(null, "config.yaml");
            var tiko = framework.loadClass(Tiko.class.getName());
            var create = tiko.getMethod("create", framework.loadClass("io.tiko.ConfigSource"));
            var container = (AutoCloseable) create.invoke(null, configSource);
            try (container) {
                // Through the interface: the handle Tiko returns is a private class.
                var get = framework.loadClass(Container.class.getName()).getMethod("get", Class.class);
                var service = get.invoke(container, application.loadClass(DataService.class.getName()));

                assertThat(service.getClass().getClassLoader())
                        .as("the component comes from the application's own loader")
                        .isSameAs(application);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
