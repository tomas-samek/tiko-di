package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AggregatingContainerShadowRoutingTest {

    @Test
    void shadowDeclarationRegistersAsOverrideOnSharedOptions(@TempDir Path tmp) throws Exception {
        Path meta = tmp.resolve("META-INF").resolve("tiko");
        Files.createDirectories(meta);

        Properties testDescriptor = new Properties();
        testDescriptor.setProperty("impl", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-container.properties"))) {
            testDescriptor.store(out, "test");
        }
        // Aggregator's loadComponentsMapping reads sibling components.txt; an empty file is fine.
        Files.writeString(meta.resolve("components.txt"), "");

        Properties shadows = new Properties();
        shadows.setProperty("java.lang.String", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-shadows.properties"))) {
            shadows.store(out, "test");
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[] {tmp.toUri().toURL()}, AggregatingContainerShadowRoutingTest.class.getClassLoader());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);

        try {
            TikoOptions opts = TikoOptions.builder().build();
            new AggregatingContainer(
                    new LocalEventBus(),
                    ctx -> {},
                    null,
                    java.time.Duration.ZERO,
                    opts,
                    "META-INF/tiko/test-container.properties");

            assertThat(opts.hasOverride(String.class)).isTrue();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void userOverrideWinsOverShadowDeclaration(@TempDir Path tmp) throws Exception {
        Path meta = tmp.resolve("META-INF").resolve("tiko");
        Files.createDirectories(meta);

        Properties testDescriptor = new Properties();
        testDescriptor.setProperty("impl", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-container.properties"))) {
            testDescriptor.store(out, "test");
        }
        Files.writeString(meta.resolve("components.txt"), "");

        Properties shadows = new Properties();
        shadows.setProperty("java.lang.String", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-shadows.properties"))) {
            shadows.store(out, "test");
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[] {tmp.toUri().toURL()}, AggregatingContainerShadowRoutingTest.class.getClassLoader());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);

        try {
            TikoOptions opts = TikoOptions.builder()
                    .override(String.class, () -> "user-wins")
                    .build();
            new AggregatingContainer(
                    new LocalEventBus(),
                    ctx -> {},
                    null,
                    java.time.Duration.ZERO,
                    opts,
                    "META-INF/tiko/test-container.properties");

            // User override stays put — IfAbsent semantics yield to user.
            assertThat(opts.getOverride(String.class).get()).isEqualTo("user-wins");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
