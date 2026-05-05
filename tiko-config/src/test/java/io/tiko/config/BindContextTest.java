// tiko-config/src/test/java/io/tiko/config/BindContextTest.java
package io.tiko.config;

import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.TypeCoercer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BindContextTest {

    @Test
    void requireSection_returns_existing_map_or_empty_with_error() {
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> root = Map.of("db", Map.of("url", "x"));

        Map<String, Object> db = ctx.requireSection(root, "db");
        assertThat(db).containsEntry("url", "x");
        assertThat(ctx.hasErrors()).isFalse();

        Map<String, Object> missing = ctx.requireSection(root, "kafka");
        assertThat(missing).isEmpty();
        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void requireScalar_uses_coercer_and_emits_error_on_absent_key() {
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("port", "8080");

        TypeCoercer<Integer> intC = Coercers.intCoercer();
        int port = ctx.requireScalar(node, "port", "db.port", intC, 0);
        assertThat(port).isEqualTo(8080);

        int missing = ctx.requireScalar(node, "host", "db.host", Coercers.stringCoercer().getClass() == intC.getClass() ? intC : intC, 0);
        assertThat(ctx.hasErrors()).isTrue();
        assertThat(missing).isZero();
    }

    @Test
    void scalarOrDefault_uses_default_when_absent() {
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> node = Map.of();

        int v = ctx.scalarOrDefault(node, "max", "db.max", Coercers.intCoercer(), 10);
        assertThat(v).isEqualTo(10);
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void optionalScalar_returns_empty_when_absent() {
        BindContext ctx = new BindContext("c.yaml");
        Optional<Integer> v = ctx.optionalScalar(Map.of(), "x", "db.x", Coercers.intCoercer());
        assertThat(v).isEmpty();
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void checkUnknownKeys_emits_one_error_per_extra_key() {
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> node = Map.of("url", "x", "foo", "y", "bar", "z");

        ctx.checkUnknownKeys(node, "db", Set.of("url"));
        assertThat(ctx.errors()).hasSize(2);
        assertThat(ctx.errors().get(0).message()).contains("unknown");
    }
}
