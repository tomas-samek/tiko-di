package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #497: a jar carrying test wiring ({@code test-container.properties} + {@code test-shadows.properties},
 * as the processor writes for a {@code @TestComponent}) on a production classpath must not change
 * which implementation {@code get(...)} returns, and must not go unnoticed.
 */
class StrayTestWiringTest {

    @TempDir
    Path fixturesJar;

    private ClassLoader previous;

    @BeforeEach
    void putFixturesJarOnTheClasspath() throws Exception {
        Path meta = Files.createDirectories(fixturesJar.resolve("META-INF").resolve("tiko"));
        Files.writeString(meta.resolve("test-container.properties"), "impl=io.tiko.runtime.FixtureTestContainer\n");
        Files.writeString(meta.resolve("components.txt"), "");
        Files.writeString(
                meta.resolve("test-shadows.properties"),
                "io.tiko.runtime.StubService=io.tiko.runtime.FixtureTestContainer|io.tiko.runtime.FakeStubService\n");
        previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread()
                .setContextClassLoader(new URLClassLoader(
                        new URL[] {fixturesJar.toUri().toURL()}, getClass().getClassLoader()));
        CapturingLoggerFinder.clear();
    }

    @AfterEach
    void restoreClassLoader() {
        Thread.currentThread().setContextClassLoader(previous);
    }

    @Test
    void strayTestWiringDoesNotChangeWhatGetReturns() {
        try (Container c = Tiko.create(TikoOptions.builder().build())) {
            assertThat(c.get(StubService.class)).isSameAs(StubContainer.STUB_SERVICE);
        }
    }

    @Test
    void optedInTestWiringAppliesTheShadow() {
        try (Container c = Tiko.create(TikoOptions.builder().testWiring(true).build())) {
            assertThat(c.get(StubService.class)).isSameAs(FakeStubService.INSTANCE);
            assertThat(CapturingLoggerFinder.RECORDS)
                    .noneMatch(r -> r.message() != null && r.message().contains("Ignoring test wiring"));
        }
    }

    @Test
    void strayTestWiringIsReportedAtStartup() throws Exception {
        String jarLocation = fixturesJar.toUri().toURL().toString(); // the form the class loader reports
        try (Container c = Tiko.create(TikoOptions.builder().build())) {
            assertThat(CapturingLoggerFinder.RECORDS)
                    .filteredOn(r -> r.level() == System.Logger.Level.WARNING)
                    .anySatisfy(r -> assertThat(r.message())
                            .contains(jarLocation + "META-INF/tiko/test-container.properties", "testWiring(true)"));
        }
    }
}
