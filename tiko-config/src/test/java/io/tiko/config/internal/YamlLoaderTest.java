package io.tiko.config.internal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlLoaderTest {

    @Test
    void parses_simple_mapping_to_nested_maps() {
        String yaml = """
                db:
                  url: jdbc:postgres
                  maxConnections: 10
                """;
        Map<String, Object> tree = YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> db = (Map<String, Object>) tree.get("db");
        assertThat(db).containsEntry("url", "jdbc:postgres").containsEntry("maxConnections", 10);
    }

    @Test
    void parses_lists_and_nested_mappings() {
        String yaml = """
                servers:
                  - host: a
                    port: 1
                  - host: b
                    port: 2
                """;
        Map<String, Object> tree = YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        List<?> servers = (List<?>) tree.get("servers");
        assertThat(servers).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstServer = (Map<String, Object>) servers.get(0);
        assertThat(firstServer).containsEntry("host", "a");
    }

    @Test
    void empty_document_returns_empty_map() {
        Map<String, Object> tree = YamlLoader.load(new ByteArrayInputStream(new byte[0]));
        assertThat(tree).isEmpty();
    }

    @Test
    void malformed_yaml_throws_runtime_exception_with_message() {
        String yaml = "db:\n  url: [unclosed\n";
        assertThatThrownBy(() -> YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(RuntimeException.class);
    }
}
