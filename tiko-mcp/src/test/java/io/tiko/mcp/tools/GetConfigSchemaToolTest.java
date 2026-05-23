package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GetConfigSchemaToolTest {

    @Test
    void noPrefixReturnsFullSchema(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"object",
                 "properties":{"database":{"type":"object","properties":{}}}}
                """);
        var tool = new GetConfigSchemaTool(store);

        Object schema = tool.execute(Map.of()).get("schema");
        assertThat(schema).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) schema).get("$schema")).isEqualTo("https://json-schema.org/draft/2020-12/schema");
    }

    @Test
    void prefixReturnsSlice(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"object",
                 "properties":{
                    "database":{"type":"object","title":"DbCfg"},
                    "cache":{"type":"object","title":"CacheCfg"}
                 }}
                """);
        var tool = new GetConfigSchemaTool(store);

        Map<?, ?> slice = (Map<?, ?>) tool.execute(Map.of("prefix", "cache")).get("schema");
        assertThat(slice.get("title")).isEqualTo("CacheCfg");
    }

    @Test
    void unknownPrefixThrows(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"object",
                 "properties":{"database":{"type":"object"}}}
                """);
        var tool = new GetConfigSchemaTool(store);

        assertThatThrownBy(() -> tool.execute(Map.of("prefix", "nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");
    }

    private TopologyStore storeWith(Path root, String schemaJson) throws Exception {
        Path f = root.resolve("m/target/classes/META-INF/tiko/config-schema.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, schemaJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
