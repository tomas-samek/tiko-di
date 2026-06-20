package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards #413: a project scaffolded from the archetype must actually run its integration tests.
 *
 * <p>The generated pom has no parent, so while surefire's {@code *Test} run is bound by the default
 * lifecycle, the failsafe execution that runs {@code *IT} classes at the {@code verify} phase is
 * not — it has to be declared in the archetype's own pom template. Without it, {@code mvn verify}
 * reports {@code BUILD SUCCESS} while running zero integration tests, and a scaffolded app needs a
 * JUnit dependency on the test classpath to write that test in the first place.
 */
class ArchetypeRunsIntegrationTestsTest {

    private static final Path GENERATED_POM = Path.of("src", "main", "resources", "archetype-resources", "pom.xml");

    @Test
    void generatedPomWiresFailsafeToRunIntegrationTests() throws IOException {
        String pom = Files.readString(GENERATED_POM);
        assertThat(pom)
                .as("scaffolded apps need the failsafe plugin to execute *IT classes at verify")
                .contains("maven-failsafe-plugin")
                .contains("<goal>integration-test</goal>")
                .contains("<goal>verify</goal>");
    }

    @Test
    void generatedPomProvidesAJUnitTestDependency() throws IOException {
        String pom = Files.readString(GENERATED_POM);
        assertThat(pom)
                .as("a scaffolded app must be able to write the integration test that failsafe runs")
                .contains("junit-jupiter");
    }
}
