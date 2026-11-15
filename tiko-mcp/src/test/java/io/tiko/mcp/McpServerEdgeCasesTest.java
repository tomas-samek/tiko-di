package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Input-handling edges of the MCP server found in the 0.7.0 review (#762). */
class McpServerEdgeCasesTest {

    @Test
    void serverReportsTheVersionOfItsArtifact() throws Exception {
        // Surefire runs in the module directory; the module inherits its version from the parent.
        var pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        int parent = pom.indexOf("<parent>");
        int version = pom.indexOf("<version>", parent);
        assertThat(parent).as("tiko-mcp/pom.xml has a parent").isNotNegative();
        assertThat(version).as("the parent states its version").isBetween(parent, pom.indexOf("</parent>"));
        var parentVersion = pom.substring(version + "<version>".length(), pom.indexOf("</version>", version));

        assertThat(McpStdioBridge.serverVersion()).isEqualTo(parentVersion.strip());
    }

    @Test
    void runReturnsWhenTheInputEnds() {
        var bridge = new McpStdioBridge(List.of());
        var input = new ByteArrayInputStream(new byte[0]);
        var output = new ByteArrayOutputStream();

        var run = CompletableFuture.runAsync(() -> bridge.run(input, output));

        assertThat(run).succeedsWithin(Duration.ofSeconds(10));
    }

    @Test
    void errorOfAFailingHandlerIsValidJsonWhateverTheMessage() {
        var message = "tab\there, \"quoted\", back\\slash, line\r\nbreak, control\u0001, form\ffeed";
        var spec = McpStdioBridge.spec(
                McpJsonDefaults.getMapper(), new ToolRegistration("failing", "Fails", "{\"type\":\"object\"}", args -> {
                    throw new IllegalArgumentException(message);
                }));

        var result = spec.callHandler().apply(null, new McpSchema.CallToolRequest("failing", Map.of()));

        assertThat(result.isError()).isTrue();
        var text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(JsonReader.parse(text)).isEqualTo(Map.of("error", message));
    }

    static Stream<Arguments> truncated() {
        return Stream.of(
                Arguments.of("after a value in an object", "{\"a\": 1"),
                Arguments.of("after a value in an array", "{\"a\": [1, 2"),
                Arguments.of("after a comma", "[1,"),
                Arguments.of("after a key", "{\"a\""),
                Arguments.of("inside a string", "{\"a\": \"b"),
                Arguments.of("after a sign", "-"),
                Arguments.of("empty", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("truncated")
    void truncatedJsonIsReportedAsSuch(String name, String json) {
        assertThatThrownBy(() -> JsonReader.parse(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatedTopologyFileIsReportedWithItsPath(@TempDir Path root) throws Exception {
        var file = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file, "{\"schemaVersion\":1,\"components\":[{\"qualifiedName\":\"x.A\"", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TopologyStore.loadFrom(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topology.json")
                .hasMessageContaining("rebuild");
    }
}
