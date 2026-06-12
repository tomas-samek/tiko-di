package io.tiko.config.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tiko.ConfigIssueCode;
import io.tiko.SourceLocation;
import io.tiko.config.ConfigValidationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * Verifies {@link YamlLoader} produces both the data map (today's
 * behaviour) AND a parallel location index keyed by dot-path.
 */
class YamlLoaderTest {

    private static final String YAML =
            "db:\n" + "  url: jdbc:postgres://localhost\n" + "  poolSize: 10\n" + "app:\n" + "  name: example\n";

    @Test
    void loadProducesDataMap() {
        YamlLoader.LoadedYaml loaded =
                YamlLoader.load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)), "test.yaml");

        assertThat(loaded.data()).containsKeys("db", "app");
        @SuppressWarnings("unchecked")
        var dbMap = (java.util.Map<String, Object>) loaded.data().get("db");
        // Scalars arrive as literal text since #343 — the binder's coercers parse them
        // against the record component's declared type.
        assertThat(dbMap).containsEntry("url", "jdbc:postgres://localhost").containsEntry("poolSize", "10");
    }

    @Test
    void loadProducesLocationIndexForLeafScalars() {
        YamlLoader.LoadedYaml loaded =
                YamlLoader.load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)), "test.yaml");

        SourceLocation urlLoc = loaded.locations().get("db.url");
        assertThat(urlLoc).isNotNull();
        assertThat(urlLoc.source()).isEqualTo("test.yaml");
        assertThat(urlLoc.line()).isEqualTo(2); // "  url: ..." is line 2 (1-based)
    }

    @Test
    void loadProducesLocationIndexForSectionHeaders() {
        YamlLoader.LoadedYaml loaded =
                YamlLoader.load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)), "test.yaml");

        SourceLocation dbLoc = loaded.locations().get("db");
        assertThat(dbLoc).isNotNull();
        assertThat(dbLoc.line()).isEqualTo(1); // "db:" is line 1
    }

    @Test
    void loadEmptyYamlProducesEmptyMaps() {
        YamlLoader.LoadedYaml loaded =
                YamlLoader.load(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)), "empty.yaml");

        assertThat(loaded.data()).isEmpty();
        assertThat(loaded.locations()).isEmpty();
    }

    @Test
    void loadProducesScalarSequence() {
        String yaml = "ports:\n  - 8080\n  - 8081\n  - 8082\n";
        YamlLoader.LoadedYaml loaded =
                YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "seq.yaml");

        assertThat(loaded.data().get("ports")).isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        var ports = (java.util.List<Object>) loaded.data().get("ports");
        assertThat(ports).containsExactly("8080", "8081", "8082");
    }

    @Test
    void loadProducesNestedMappingsInsideSequence() {
        String yaml = "endpoints:\n  - host: a\n    port: 1\n  - host: b\n    port: 2\n";
        YamlLoader.LoadedYaml loaded =
                YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "endpoints.yaml");

        @SuppressWarnings("unchecked")
        var endpoints = (java.util.List<Object>) loaded.data().get("endpoints");
        assertThat(endpoints).hasSize(2);
        @SuppressWarnings("unchecked")
        var first = (java.util.Map<String, Object>) endpoints.get(0);
        assertThat(first).containsEntry("host", "a").containsEntry("port", "1");
    }

    @Test
    void loadHandlesNestedSequences() {
        String yaml = "matrix:\n  - [1, 2]\n  - [3, 4]\n";
        YamlLoader.LoadedYaml loaded =
                YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "matrix.yaml");

        @SuppressWarnings("unchecked")
        var matrix = (java.util.List<Object>) loaded.data().get("matrix");
        assertThat(matrix).hasSize(2);
        assertThat(matrix.get(0)).isInstanceOf(java.util.List.class);
    }

    @Test
    void loadHandlesNullScalarValue() {
        // YAML null appears as an unquoted blank or "~"
        String yaml = "feature:\n  enabled: ~\n";
        YamlLoader.LoadedYaml loaded =
                YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "null.yaml");

        @SuppressWarnings("unchecked")
        var feature = (java.util.Map<String, Object>) loaded.data().get("feature");
        assertThat(feature).containsEntry("enabled", null);
    }

    @Test
    void nonMappingRootThrowsIllegalArgumentException() {
        // A bare scalar at the root — YAML accepts it but YamlLoader requires a top-level mapping.
        String scalarRoot = "just-a-string\n";
        var in = new ByteArrayInputStream(scalarRoot.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> YamlLoader.load(in, "scalar.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YAML root must be a mapping");
    }

    @Test
    void malformedYamlThrowsTikoErrorAnchoredAtFileAndLineNotRawSnakeYaml() {
        // Unclosed flow sequence — a syntax error SnakeYAML reports as a MarkedYAMLException.
        String malformed = "service:\n  ports: [8080, 9090\n";
        var in = new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8));

        ConfigValidationException ex =
                catchThrowableOfType(ConfigValidationException.class, () -> YamlLoader.load(in, "broken.yaml"));

        assertThat(ex)
                .as("malformed YAML must surface as a Tiko config error, not raw SnakeYAML")
                .isNotNull();
        assertThat(ex).isNotInstanceOf(MarkedYAMLException.class);
        assertThat(ex.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(ConfigIssueCode.INVALID_VALUE);
            // Anchored at file:line:col so it reads like every other config failure.
            assertThat(issue.description()).matches(".*broken\\.yaml:\\d+:\\d+.*");
        });
        assertThat(ex.getMessage()).contains("broken.yaml");
    }
}
