// tiko-config/src/test/java/io/tiko/config/internal/InterpolatorTest.java
package io.tiko.config.internal;

import io.tiko.config.BindContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class InterpolatorTest {

    Function<String, String> env(Map<String, String> entries) {
        return entries::get;
    }

    @Test
    void substitutes_present_variable() {
        Map<String, Object> tree = Map.of("url", "${DB_URL}");
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(
            tree, env(Map.of("DB_URL", "jdbc:postgres://x")), ctx);
        assertThat(result).containsEntry("url", "jdbc:postgres://x");
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void uses_default_when_variable_absent() {
        Map<String, Object> tree = Map.of("url", "${DB_URL:jdbc:default}");
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(tree, env(Map.of()), ctx);
        assertThat(result).containsEntry("url", "jdbc:default");
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void emits_error_when_variable_absent_and_no_default() {
        Map<String, Object> tree = Map.of("url", "${DB_URL}");
        BindContext ctx = new BindContext("c.yaml");
        Interpolator.interpolate(tree, env(Map.of()), ctx);
        assertThat(ctx.hasErrors()).isTrue();
        assertThat(ctx.errors().get(0).message()).contains("DB_URL");
    }

    @Test
    void multiple_substitutions_per_scalar() {
        Map<String, Object> tree = Map.of("conn", "${HOST}:${PORT}");
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(
            tree, env(Map.of("HOST", "h", "PORT", "9090")), ctx);
        assertThat(result).containsEntry("conn", "h:9090");
    }

    @Test
    void recurses_into_nested_maps_and_lists() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("a", "${A}");
        inner.put("b", List.of("${B}", "literal"));
        Map<String, Object> tree = Map.of("inner", inner);

        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(
            tree, env(Map.of("A", "alpha", "B", "beta")), ctx);

        Map<String, Object> r = (Map<String, Object>) result.get("inner");
        assertThat(r).containsEntry("a", "alpha");
        @SuppressWarnings("unchecked")
        List<Object> bList = (List<Object>) r.get("b");
        assertThat(bList).containsExactly("beta", "literal");
    }

    @Test
    void leaves_keys_untouched() {
        Map<String, Object> tree = Map.of("${LITERAL_KEY}", "value");
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(
            tree, env(Map.of("LITERAL_KEY", "ignored")), ctx);
        assertThat(result).containsKey("${LITERAL_KEY}");
    }
}
