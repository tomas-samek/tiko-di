package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SEC-13: a project started from the archetype or from the Kotlin starter (the source of the
 * {@code tiko-kotlin-starter} template repository) resolves only exact released coordinates from Maven
 * Central. The release workflow bumps the {@code tiko-mcp} coordinate only when it is an exact
 * {@code X.Y.Z}, so anything else would also stay unbumped.
 */
class GeneratedProjectsStartSafeTest {

    /** Paths relative to the {@code tiko-archetype} module directory. */
    private static final Path KOTLIN_STARTER = Path.of("..", "tiko-examples-gradle", "kotlin-starter");

    private static final Path ARCHETYPE_MCP_CONFIG =
            Path.of("src", "main", "resources", "archetype-resources", ".mcp.json");

    private static final Pattern REPOSITORIES_BLOCK =
            Pattern.compile("(?m)^repositories \\{\\R(.*?)^}", Pattern.DOTALL);
    private static final Pattern SNAPSHOT_ONLY_MAVEN_LOCAL =
            Pattern.compile("if \\(tikoVersion\\.endsWith\\(\"-SNAPSHOT\"\\)\\) \\{\\s*mavenLocal\\(\\)\\s*}");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)//.*$");
    private static final Pattern MCP_COORDINATE = Pattern.compile("io\\.github\\.tomas-samek:tiko-mcp:([^\"]+)\"");

    @Test
    void theKotlinStarterResolvesFromMavenCentralOnceItNamesARelease() throws IOException {
        var build = Files.readString(KOTLIN_STARTER.resolve("build.gradle.kts"));
        var block = REPOSITORIES_BLOCK.matcher(build);
        assertThat(block.find())
                .as("build.gradle.kts declares a top-level repositories block")
                .isTrue();

        var outsideTheSnapshotGuard = SNAPSHOT_ONLY_MAVEN_LOCAL
                .matcher(LINE_COMMENT.matcher(block.group(1)).replaceAll(""))
                .replaceAll("");

        assertThat(outsideTheSnapshotGuard.lines().map(String::strip).filter(line -> !line.isEmpty()))
                .as(
                        "repositories a release of the starter resolves from; mavenLocal() only for a -SNAPSHOT tikoVersion")
                .containsExactly("mavenCentral()");
    }

    static Stream<Path> mcpConfigs() {
        return Stream.of(ARCHETYPE_MCP_CONFIG, KOTLIN_STARTER.resolve(".mcp.json"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mcpConfigs")
    void mcpConfigRunsAnExactReleasedTikoMcp(Path config) throws IOException {
        var coordinate = MCP_COORDINATE.matcher(Files.readString(config));

        assertThat(coordinate.find())
                .as("%s runs io.github.tomas-samek:tiko-mcp", config)
                .isTrue();
        assertThat(coordinate.group(1))
                .as("%s: the tiko-mcp version a new project runs", config)
                .matches("\\d+\\.\\d+\\.\\d+");
    }
}
