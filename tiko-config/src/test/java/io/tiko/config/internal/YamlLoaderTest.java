package io.tiko.config.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.SourceLocation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

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
        assertThat(dbMap).containsEntry("url", "jdbc:postgres://localhost").containsEntry("poolSize", 10);
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
}
