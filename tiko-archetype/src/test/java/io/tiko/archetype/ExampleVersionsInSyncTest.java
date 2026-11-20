package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #691: no release step bumps the example READMEs, so a version written into one goes stale at the next
 * release or snapshot bump. Example 13's {@code .mcp.json} runs the locally built tiko-mcp jar; the release
 * workflow bumps its jar name at both steps, so it must name the reactor version. {@code 15_quickstart} has no
 * parent (#854), so a copy of it builds on its own; the release workflow sets its {@code <tiko.version>} at both
 * steps, so it must name the reactor version too.
 */
class ExampleVersionsInSyncTest {

    /** Paths relative to the {@code tiko-archetype} module directory. */
    private static final Path ROOT_POM = Path.of("..", "pom.xml");

    private static final Path MCP_EXAMPLE_CONFIG = Path.of("..", "tiko-examples", "13_mcp_introspection", ".mcp.json");
    private static final Path STANDALONE_QUICKSTART = Path.of("..", "tiko-examples", "15_quickstart", "pom.xml");
    private static final Set<String> SKIPPED_DIRS = Set.of("target", "build", ".gradle", "node_modules");

    private static final Pattern REACTOR_VERSION =
            Pattern.compile("<artifactId>tiko-parent</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern SNAPSHOT_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+-SNAPSHOT");
    private static final Pattern VERSIONED_JAR = Pattern.compile("[\\w.-]+-\\d+\\.\\d+\\.\\d+(?:-SNAPSHOT)?\\.jar");
    private static final Pattern MCP_JAR = Pattern.compile("tiko-mcp-([^\"/]+)\\.jar");
    private static final Pattern TIKO_VERSION = Pattern.compile("<tiko\\.version>([^<]+)</tiko\\.version>");

    static Stream<Path> exampleReadmes() {
        return Stream.of(Path.of("..", "tiko-examples"), Path.of("..", "tiko-examples-gradle"))
                .flatMap(ExampleVersionsInSyncTest::readmesUnder);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exampleReadmes")
    void exampleReadmeNamesNoProjectVersion(Path readme) throws IOException {
        var text = Files.readString(readme);

        assertThat(SNAPSHOT_VERSION.matcher(text).results().map(m -> m.group()))
                .as("%s names a -SNAPSHOT version; no release step bumps README text", readme)
                .isEmpty();
        assertThat(VERSIONED_JAR.matcher(text).results().map(m -> m.group()))
                .as("%s names a versioned jar; no release step bumps README text", readme)
                .isEmpty();
    }

    @Test
    void mcpExampleRunsTheReactorVersionOfTikoMcp() throws IOException {
        var reactor = firstGroup(REACTOR_VERSION, Files.readString(ROOT_POM));

        assertThat(firstGroup(MCP_JAR, Files.readString(MCP_EXAMPLE_CONFIG)))
                .as("%s must run the tiko-mcp jar of the reactor version", MCP_EXAMPLE_CONFIG)
                .isEqualTo(reactor);
    }

    @Test
    void standaloneQuickstartBuildsAgainstTheReactorVersion() throws IOException {
        var reactor = firstGroup(REACTOR_VERSION, Files.readString(ROOT_POM));
        var quickstart = Files.readString(STANDALONE_QUICKSTART);

        assertThat(quickstart)
                .as("%s builds on its own when copied out of the repository", STANDALONE_QUICKSTART)
                .doesNotContain("<parent>");
        assertThat(firstGroup(TIKO_VERSION, quickstart))
                .as("%s must build against the reactor version of Tiko", STANDALONE_QUICKSTART)
                .isEqualTo(reactor);
    }

    private static Stream<Path> readmesUnder(Path root) {
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.getFileName().toString().equals("README.md"))
                    .filter(path -> !inSkippedDir(root.relativize(path)))
                    .sorted()
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list " + root, e);
        }
    }

    private static boolean inSkippedDir(Path relative) {
        for (var segment : relative) {
            if (SKIPPED_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String firstGroup(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        assertThat(matcher.find()).as("pattern %s not found", pattern).isTrue();
        return matcher.group(1);
    }
}
