package io.tiko.examples.quickstart;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Javalin 6 runs on Jetty 11, which has five open advisories with no fixed 11.x on Maven Central
 * (GHSA-355h-qmc2-wpwf, GHSA-qh8g-58pp-2wxh, GHSA-wjpw-4j6x-6rwh, GHSA-2fvj-hgj9-j2gr,
 * GHSA-7p3p-8qv8-m2vh). Javalin 7 runs on Jetty 12.1; 12.1.10 is the first release with all five
 * fixes (#507). The HTTP examples share the root-managed Javalin version, so one guard covers them.
 */
class JettyVersionTest {

    /** Jars of {@code org.eclipse.jetty} and {@code org.eclipse.jetty.eeN}, not the separately versioned toolchain. */
    private static final Pattern JETTY_JAR = Pattern.compile(
            ".*[/\\\\]org[/\\\\]eclipse[/\\\\]jetty[/\\\\](?:ee\\d+[/\\\\])?([^/\\\\]+)[/\\\\](\\d+\\.\\d+\\.\\d+)[/\\\\][^/\\\\]+\\.jar$");

    private static final int[] FIRST_FIXED = {12, 1, 10};

    @Test
    void everyJettyJarOnTheClasspathHasTheAdvisoryFixes() {
        var jettyJars = Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(JETTY_JAR::matcher)
                .filter(Matcher::matches)
                .toList();

        assertThat(jettyJars).as("Jetty jars resolved for the quickstart").isNotEmpty();
        assertThat(jettyJars)
                .allSatisfy(m -> assertThat(atLeast(m.group(2), FIRST_FIXED))
                        .as("%s %s must be >= 12.1.10", m.group(1), m.group(2))
                        .isTrue());
    }

    private static boolean atLeast(String version, int[] floor) {
        int[] parts =
                Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
        for (int i = 0; i < floor.length; i++) {
            if (parts[i] != floor[i]) return parts[i] > floor[i];
        }
        return true;
    }
}
