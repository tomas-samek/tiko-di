package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.TikoModule;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #537: a shaded fat jar of a multi-module app keeps one copy of the fixed-name
 * {@code META-INF/tiko/container.properties} (here: the test classpath's, for {@link StubContainer}),
 * but merges {@code META-INF/services}. Every module listed there must boot.
 */
class MultiModuleFatJarTest {

    @Test
    void everyModuleListedAsAServiceBootsAlthoughOnlyOneLegacyDescriptorSurvives(@TempDir Path fatJar)
            throws Exception {
        module(fatJar, "stub", StubContainer.class, "");
        module(fatJar, "fixture", FixtureTestContainer.class, FakeStubService.class.getName());
        Path services = Files.createDirectories(fatJar.resolve("META-INF").resolve("services"));
        Files.writeString(
                services.resolve(TikoModule.class.getName()),
                TestStubModule.class.getName() + "\n" + TestFixtureModule.class.getName() + "\n");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var cl = new URLClassLoader(
                new URL[] {fatJar.toUri().toURL()}, getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(cl);
            try (Container c = Tiko.create(TikoOptions.builder().build())) {
                assertThat(c.get(StubService.class))
                        .as("the module the legacy descriptor names")
                        .isNotNull();
                assertThat(c.get(FakeStubService.class))
                        .as("the module only its services entry names")
                        .isSameAs(FakeStubService.INSTANCE);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** A services entry for a module class that is gone fails startup with a clear message. */
    @Test
    void aListedModuleThatCannotBeLoadedFailsStartupClearly(@TempDir Path jar) throws Exception {
        Path services = Files.createDirectories(jar.resolve("META-INF").resolve("services"));
        Files.writeString(services.resolve(TikoModule.class.getName()), "io.tiko.generated.TikoModule_gone\n");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var cl =
                new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(cl);
            TikoOptions options = TikoOptions.builder().build();
            assertThatThrownBy(() -> Tiko.create(options))
                    .isInstanceOf(ContainerInitializationException.class)
                    .hasMessageContaining("META-INF/services/" + TikoModule.class.getName());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static void module(Path jar, String name, Class<?> container, String components) throws IOException {
        Path dir = Files.createDirectories(jar.resolve("META-INF/tiko/modules").resolve(name));
        Files.writeString(dir.resolve("container.properties"), "impl=" + container.getName() + "\n");
        Files.writeString(dir.resolve("components.txt"), components.isEmpty() ? "" : components + "\n");
    }
}
