package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TikoTestDescriptorRoutingTest {

    @Test
    void singleModuleTestDescriptorRoutesThroughAggregatingContainer(@TempDir Path tmp) throws Exception {
        // Even with exactly 1 test-container.properties on the classpath, Tiko.createInternal
        // must use AggregatingContainer (not the single-module fast path) so that
        // shadow registration runs.

        Path meta = tmp.resolve("META-INF").resolve("tiko");
        Files.createDirectories(meta);

        Properties testDescriptor = new Properties();
        testDescriptor.setProperty("impl", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-container.properties"))) {
            testDescriptor.store(out, "test");
        }
        Files.writeString(meta.resolve("components.txt"), ""); // StubContainer expects this sibling

        URLClassLoader cl = new URLClassLoader(
                new URL[] {tmp.toUri().toURL()}, TikoTestDescriptorRoutingTest.class.getClassLoader());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);

        try (Container c = Tiko.create(TikoOptions.builder().build())) {
            // The container should be (or wrap) an AggregatingContainer instance.
            // TransportAwareContainer may wrap the inner container; unwrap if needed.
            Container inner = c;
            try {
                var delegateField = inner.getClass().getDeclaredField("delegate");
                delegateField.setAccessible(true);
                Object delegate = delegateField.get(inner);
                if (delegate instanceof Container delegateContainer) {
                    inner = delegateContainer;
                }
            } catch (NoSuchFieldException ignored) {
                // Not wrapped — `inner` is already the container of interest.
            }
            assertThat(inner.getClass().getName()).isEqualTo("io.tiko.runtime.AggregatingContainer");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
