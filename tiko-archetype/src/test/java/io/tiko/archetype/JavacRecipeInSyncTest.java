package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drift gate for the plain-{@code javac} recipe in {@code docs/jdk-23-setup.md}. Its processor path names the
 * third-party jars the processor needs by file name, version included; those versions are properties of the
 * root POM and go stale silently when a dependency is bumped.
 */
class JavacRecipeInSyncTest {

    /** Paths relative to the {@code tiko-archetype} module directory. */
    private static final Path RECIPE = Path.of("..", "docs", "jdk-23-setup.md");

    private static final Path ROOT_POM = Path.of("..", "pom.xml");

    static Stream<Arguments> thirdPartyJars() {
        return Stream.of(Arguments.of("snakeyaml", "snakeyaml.version"), Arguments.of("javapoet", "javapoet.version"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("thirdPartyJars")
    void processorPathNamesTheJarVersionTheRootPomPins(String artifact, String property) throws IOException {
        var version = pomProperty(property);

        assertThat(Files.readString(RECIPE))
                .as(
                        "docs/jdk-23-setup.md plain-javac recipe must name %s-%s.jar, the version of <%s> in the"
                                + " root pom.xml.",
                        artifact, version, property)
                .contains(artifact + "-" + version + ".jar");
    }

    private static String pomProperty(String name) throws IOException {
        var matcher = Pattern.compile("<" + Pattern.quote(name) + ">([^<]+)</" + Pattern.quote(name) + ">")
                .matcher(Files.readString(ROOT_POM));
        assertThat(matcher.find())
                .as("root pom.xml declares the <%s> property", name)
                .isTrue();
        return matcher.group(1).strip();
    }
}
