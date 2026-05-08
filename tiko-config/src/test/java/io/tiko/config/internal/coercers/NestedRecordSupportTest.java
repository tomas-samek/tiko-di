// tiko-config/src/test/java/io/tiko/config/internal/coercers/NestedRecordSupportTest.java
package io.tiko.config.internal.coercers;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NestedRecordSupportTest {

    @Test
    void requireMap_returns_mutable_copy() {
        Map<String, Object> raw = Map.of("a", 1);
        Map<String, Object> got = NestedRecordSupport.requireMap(raw, "X");
        assertThat(got).containsEntry("a", 1);
        // Verify the returned map is mutable — bind logic removes consumed keys.
        got.remove("a");
        assertThat(got).isEmpty();
    }

    @Test
    void requireMap_throws_on_non_map_input() {
        assertThatThrownBy(() -> NestedRecordSupport.requireMap("not a map", "X"))
            .isInstanceOf(CoercionException.class)
            .hasMessageContaining("expected mapping for nested record 'X'");
    }

    @Test
    void requireField_throws_on_missing_key_with_record_path() {
        Map<String, Object> node = new LinkedHashMap<>();
        assertThatThrownBy(() ->
                NestedRecordSupport.requireField(node, "url", "DbConfig", Coercers.stringCoercer()))
            .isInstanceOf(CoercionException.class)
            .hasMessage("DbConfig missing required field 'url'");
    }

    @Test
    void requireField_consumes_key_and_returns_coerced_value() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("port", "8080");
        int result = NestedRecordSupport.requireField(node, "port", "Server", Coercers.intCoercer());
        assertThat(result).isEqualTo(8080);
        assertThat(node).doesNotContainKey("port");
    }

    @Test
    void requireField_prefixes_coercion_failure_with_record_field_path() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("port", "not-a-number");
        assertThatThrownBy(() ->
                NestedRecordSupport.requireField(node, "port", "Server", Coercers.intCoercer()))
            .isInstanceOf(CoercionException.class)
            .hasMessageStartingWith("Server.port ");
    }

    @Test
    void fieldOrDefault_returns_default_when_absent() {
        Map<String, Object> node = new LinkedHashMap<>();
        int result = NestedRecordSupport.fieldOrDefault(
            node, "max", "DbConfig", Coercers.intCoercer(), 10);
        assertThat(result).isEqualTo(10);
    }

    @Test
    void optionalField_returns_empty_when_absent() {
        Map<String, Object> node = new LinkedHashMap<>();
        Optional<String> result = NestedRecordSupport.optionalField(
            node, "host", "Server", Coercers.stringCoercer());
        assertThat(result).isEmpty();
    }

    @Test
    void optionalField_returns_present_when_set() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("host", "localhost");
        Optional<String> result = NestedRecordSupport.optionalField(
            node, "host", "Server", Coercers.stringCoercer());
        assertThat(result).contains("localhost");
    }

    @Test
    void checkUnknownKeys_passes_for_empty_node() {
        NestedRecordSupport.checkUnknownKeys(new LinkedHashMap<>(), "X");
        // no exception
    }

    @Test
    void checkUnknownKeys_throws_with_record_name_and_first_unknown_key() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("typo", "x");
        assertThatThrownBy(() -> NestedRecordSupport.checkUnknownKeys(node, "DbConfig"))
            .isInstanceOf(CoercionException.class)
            .hasMessage("DbConfig unknown key 'typo'");
    }
}
