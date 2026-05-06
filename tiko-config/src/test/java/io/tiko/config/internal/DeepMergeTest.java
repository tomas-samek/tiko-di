package io.tiko.config.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepMergeTest {

    @Test
    void scalar_overwrite_last_wins() {
        Map<String, Object> result = DeepMerge.merge(
            Map.of("a", 1),
            Map.of("a", 2));
        assertThat(result).containsEntry("a", 2);
    }

    @Test
    void nested_maps_merge_recursively() {
        Map<String, Object> a = Map.of("db", Map.of("url", "x", "max", 10));
        Map<String, Object> b = Map.of("db", Map.of("max", 20));
        Map<String, Object> result = DeepMerge.merge(a, b);
        Map<String, Object> db = (Map<String, Object>) result.get("db");
        assertThat(db).containsEntry("url", "x").containsEntry("max", 20);
    }

    @Test
    void lists_replace_not_append() {
        Map<String, Object> a = Map.of("xs", List.of(1, 2));
        Map<String, Object> b = Map.of("xs", List.of(3));
        Map<String, Object> result = DeepMerge.merge(a, b);
        assertThat(result.get("xs")).isEqualTo(List.of(3));
    }

    @Test
    void chained_layers_compose_left_to_right() {
        Map<String, Object> base   = Map.of("a", 1, "b", 1);
        Map<String, Object> mid    = Map.of("b", 2, "c", 2);
        Map<String, Object> top    = Map.of("c", 3);
        Map<String, Object> result = DeepMerge.merge(DeepMerge.merge(base, mid), top);
        assertThat(result).containsEntry("a", 1).containsEntry("b", 2).containsEntry("c", 3);
    }
}
