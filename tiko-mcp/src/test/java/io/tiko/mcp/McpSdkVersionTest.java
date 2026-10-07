package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The MCP Java SDK bundled into the {@code tiko-mcp} jar is outside the ranges of
 * GHSA-8jxr-pr72-r468 ({@code < 1.0.0}) and GHSA-hv2w-8mjj-jw22 ({@code < 0.18.3}, {@code 1.0.0},
 * {@code 1.1.0}) (#502). Both flaws are in the SDK's HTTP transports, which {@code tiko-mcp} never
 * uses, but the classes ship inside the jar.
 */
class McpSdkVersionTest {

    private static final Pattern MCP_CORE_JAR = Pattern.compile(
            ".*[/\\\\]io[/\\\\]modelcontextprotocol[/\\\\]sdk[/\\\\]mcp-core[/\\\\]([^/\\\\]+)[/\\\\]mcp-core-\\1\\.jar$");

    /** First release outside both advisories' ranges that isn't one of the affected 1.x versions. */
    private static final int[] FLOOR = {1, 1, 1};

    @Test
    void bundledMcpCoreIsOutsideBothAdvisories() {
        var versions = Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(MCP_CORE_JAR::matcher)
                .filter(Matcher::matches)
                .map(m -> m.group(1))
                .toList();

        assertThat(versions).as("mcp-core resolved for tiko-mcp").hasSize(1);
        assertThat(atLeast(versions.get(0)))
                .as("mcp-core %s must be >= 1.1.1", versions.get(0))
                .isTrue();
    }

    private static boolean atLeast(String version) {
        var parts = version.split("[.-]");
        for (int i = 0; i < FLOOR.length; i++) {
            int part = i < parts.length && parts[i].matches("\\d+") ? Integer.parseInt(parts[i]) : 0;
            if (part != FLOOR[i]) return part > FLOOR[i];
        }
        return true;
    }
}
