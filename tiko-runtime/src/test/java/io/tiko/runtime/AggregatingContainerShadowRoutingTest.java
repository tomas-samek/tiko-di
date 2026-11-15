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
    void shadowDeclarationRegistersAsOverrideOnTheContainersOwnOptions(@TempDir Path tmp) throws Exception {
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
            var container = new AggregatingContainer(
                    new LocalEventBus(),
                    ctx -> {},
                    null,
                    java.time.Duration.ZERO,
                    opts,
                    "META-INF/tiko/test-container.properties");

            assertThat(container.options().hasOverride(String.class)).isTrue();
            assertThat(opts.hasOverride(String.class))
                    .as("#734: the caller's options stay as built, so they can boot another container")
                    .isFalse();
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
            var container = new AggregatingContainer(
                    new LocalEventBus(),
                    ctx -> {},
                    null,
                    java.time.Duration.ZERO,
                    opts,
                    "META-INF/tiko/test-container.properties");

            // User override stays put — IfAbsent semantics yield to user.
            assertThat(container.options().getOverride(String.class).get()).isEqualTo("user-wins");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** #497: shadow declarations are test wiring; a production (main-descriptor) aggregator never applies them. */
    @Test
    void productionAggregatorIgnoresShadowDeclarations(@TempDir Path tmp) throws Exception {
        Path meta = Files.createDirectories(tmp.resolve("META-INF").resolve("tiko"));
        Properties shadows = new Properties();
        shadows.setProperty("java.lang.String", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-shadows.properties"))) {
            shadows.store(out, "test");
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader cl = new URLClassLoader(
                new URL[] {tmp.toUri().toURL()}, AggregatingContainerShadowRoutingTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(cl);
            TikoOptions opts = TikoOptions.builder().build();
            var container = new AggregatingContainer(
                    new LocalEventBus(),
                    ctx -> {},
                    null,
                    java.time.Duration.ZERO,
                    opts,
                    "META-INF/tiko/container.properties");

            assertThat(container.options().hasOverride(String.class)).isFalse();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
