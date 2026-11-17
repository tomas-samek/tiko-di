package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Gradle examples run their checked-in {@code gradle-wrapper.jar} in CI, so each one must be
 * the wrapper Gradle published for the version its properties name (SEC-10, #810). The checksums
 * below are the official ones for {@link #GRADLE_VERSION}; a wrapper upgrade changes all three
 * constants together, from {@code https://gradle.org/release-checksums/}.
 */
class GradleWrapperChecksumTest {

    /** Relative to the {@code tiko-archetype} module directory. */
    private static final Path GRADLE_EXAMPLES = Path.of("..", "tiko-examples-gradle");

    private static final String GRADLE_VERSION = "9.8.2";
    private static final String WRAPPER_JAR_SHA256 = "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98";
    private static final String DISTRIBUTION_SHA256 =
            "21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607";

    static List<Path> wrapperDirectories() throws IOException {
        try (Stream<Path> files = Files.walk(GRADLE_EXAMPLES, 4)) {
            return files.filter(file -> file.getFileName().toString().equals("gradle-wrapper.jar"))
                    .map(Path::getParent)
                    .sorted()
                    .toList();
        }
    }

    @Test
    void everyGradleExampleIsChecked() throws IOException {
        assertThat(wrapperDirectories())
                .as("a Gradle example outside this walk would run an unchecked wrapper")
                .hasSize(4);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrapperDirectories")
    void wrapperJarIsTheOneGradlePublished(Path wrapper) throws Exception {
        assertThat(sha256(wrapper.resolve("gradle-wrapper.jar"))).isEqualTo(WRAPPER_JAR_SHA256);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrapperDirectories")
    void wrapperDownloadsThePinnedDistributionAndVerifiesIt(Path wrapper) throws IOException {
        var properties = new Properties();
        try (var in = Files.newInputStream(wrapper.resolve("gradle-wrapper.properties"))) {
            properties.load(in);
        }

        assertThat(properties)
                .containsEntry(
                        "distributionUrl",
                        "https://services.gradle.org/distributions/gradle-" + GRADLE_VERSION + "-bin.zip")
                .containsEntry("distributionSha256Sum", DISTRIBUTION_SHA256);
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
