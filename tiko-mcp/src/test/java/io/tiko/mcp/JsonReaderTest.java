package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonReaderTest {

    @Test
    void parsesPrimitives() {
        assertThat(JsonReader.parse("\"hello\"")).isEqualTo("hello");
        assertThat(JsonReader.parse("42")).isEqualTo(42L);
        assertThat(JsonReader.parse("3.14")).isEqualTo(3.14);
        assertThat(JsonReader.parse("true")).isEqualTo(true);
        assertThat(JsonReader.parse("false")).isEqualTo(false);
        assertThat(JsonReader.parse("null")).isNull();
    }

    @Test
    void parsesEscapeSequences() {
        assertThat(JsonReader.parse("\"a\\\"b\\\\c\\nd\\te\"")).isEqualTo("a\"b\\c\nd\te");
        assertThat(JsonReader.parse("\"\\u0041\"")).isEqualTo("A");
    }

    @SuppressWarnings("unchecked")
    @Test
    void parsesArray() {
        var v = JsonReader.parse("[1, \"two\", true, null]");
        assertThat(v).isInstanceOf(List.class);
        var list = (List<Object>) v;
        assertThat(list).containsExactly(1L, "two", true, null);
    }

    @SuppressWarnings("unchecked")
    @Test
    void parsesObject() {
        var v = JsonReader.parse("{\"name\": \"Tiko\", \"version\": 1, \"active\": true}");
        assertThat(v).isInstanceOf(Map.class);
        var map = (Map<String, Object>) v;
        assertThat(map)
                .containsEntry("name", "Tiko")
                .containsEntry("version", 1L)
                .containsEntry("active", true);
    }

    @SuppressWarnings("unchecked")
    @Test
    void parsesNested() {
        var v = JsonReader.parse("{\"items\":[{\"k\":\"v\"}]}");
        var map = (Map<String, Object>) v;
        var items = (List<Object>) map.get("items");
        var first = (Map<String, Object>) items.get(0);
        assertThat(first).containsEntry("k", "v");
    }

    @SuppressWarnings("unchecked")
    @Test
    void parsesPrettyPrintedTopologyShape() {
        String json = """
                {
                  "schemaVersion": 1,
                  "components": [
                    {"qualifiedName": "io.example.X", "scope": "SINGLETON"}
                  ]
                }
                """;
        var m = (Map<String, Object>) JsonReader.parse(json);
        assertThat(m.get("schemaVersion")).isEqualTo(1L);
        var components = (List<Object>) m.get("components");
        var first = (Map<String, Object>) components.get(0);
        assertThat(first.get("qualifiedName")).isEqualTo("io.example.X");
    }

    @Test
    void rejectsTrailingGarbage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JsonReader.parse("42 stuff"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
