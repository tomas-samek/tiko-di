// tiko-config/src/test/java/io/tiko/config/NestedRecordEndToEndTest.java
package io.tiko.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ConfigSource;
import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CompositeCoercers;
import io.tiko.config.internal.coercers.NestedRecordSupport;
import io.tiko.config.internal.coercers.TypeCoercer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage for #17 (nested records inside @Configuration). Uses hand-written
 * binders that mirror what {@code ConfigBinderGenerator} emits, exercising the runtime
 * pipeline (YAML map → BindContext → NestedRecordSupport → record).
 *
 * <p>Validates all four nested-record shapes:
 * <ol>
 *     <li>Direct: {@code DbConfig} field</li>
 *     <li>{@code Optional<DbConfig>}</li>
 *     <li>{@code List<Endpoint>}</li>
 *     <li>{@code Map<String, FeatureFlag>}</li>
 * </ol>
 */
class NestedRecordEndToEndTest {

    record DbConfig(String url, int max) {}

    record Endpoint(String host, int port) {}

    record FeatureFlag(boolean enabled) {}

    record AppConfig(
            DbConfig db, Optional<DbConfig> readReplica, List<Endpoint> endpoints, Map<String, FeatureFlag> flags) {}

    /** Hand-written nested coercer mirroring what ConfigBinderGenerator emits for DbConfig. */
    static TypeCoercer<DbConfig> dbCoercer() {
        return raw -> {
            Map<String, Object> node = NestedRecordSupport.requireMap(raw, "DbConfig");
            String url = NestedRecordSupport.requireField(node, "url", "DbConfig", Coercers.stringCoercer());
            int max = NestedRecordSupport.fieldOrDefault(node, "max", "DbConfig", Coercers.intCoercer(), 10);
            NestedRecordSupport.checkUnknownKeys(node, "DbConfig", java.util.Set.of("url", "max"));
            return new DbConfig(url, max);
        };
    }

    static TypeCoercer<Endpoint> endpointCoercer() {
        return raw -> {
            Map<String, Object> node = NestedRecordSupport.requireMap(raw, "Endpoint");
            String host = NestedRecordSupport.requireField(node, "host", "Endpoint", Coercers.stringCoercer());
            int port = NestedRecordSupport.requireField(node, "port", "Endpoint", Coercers.intCoercer());
            NestedRecordSupport.checkUnknownKeys(node, "Endpoint", java.util.Set.of("host", "port"));
            return new Endpoint(host, port);
        };
    }

    static TypeCoercer<FeatureFlag> flagCoercer() {
        return raw -> {
            Map<String, Object> node = NestedRecordSupport.requireMap(raw, "FeatureFlag");
            boolean enabled =
                    NestedRecordSupport.requireField(node, "enabled", "FeatureFlag", Coercers.booleanCoercer());
            NestedRecordSupport.checkUnknownKeys(node, "FeatureFlag", java.util.Set.of("enabled"));
            return new FeatureFlag(enabled);
        };
    }

    /** Hand-written top-level binder mirroring what ConfigBinderGenerator emits for AppConfig. */
    static class AppConfigBinder implements ConfigBinder<AppConfig> {
        public Class<AppConfig> type() {
            return AppConfig.class;
        }

        public String prefix() {
            return "app";
        }

        public AppConfig bind(Map<String, Object> root, BindContext ctx) {
            Map<String, Object> node = ctx.requireSection(root, "app");

            DbConfig db = ctx.requireScalar(node, "db", "app.db", dbCoercer(), null);
            Optional<DbConfig> readReplica = ctx.optionalScalar(node, "readReplica", "app.readReplica", dbCoercer());
            List<Endpoint> endpoints = ctx.requireScalar(
                    node, "endpoints", "app.endpoints", CompositeCoercers.list(endpointCoercer()), List.of());
            Map<String, FeatureFlag> flags =
                    ctx.requireScalar(node, "flags", "app.flags", CompositeCoercers.map(flagCoercer()), Map.of());

            ctx.checkUnknownKeys(node, "app", Set.of("db", "readReplica", "endpoints", "flags"));
            return new AppConfig(db, readReplica, endpoints, flags);
        }
    }

    @Test
    void all_four_nested_shapes_bind_from_yaml_like_map() {
        ConfigSource src = ConfigSources.fromMap(Map.of(
                "app",
                Map.of(
                        "db", Map.of("url", "jdbc:primary", "max", 20),
                        "readReplica", Map.of("url", "jdbc:replica"),
                        "endpoints",
                                List.of(
                                        Map.of("host", "a.local", "port", 8080),
                                        Map.of("host", "b.local", "port", 8081)),
                        "flags",
                                Map.of(
                                        "darkMode", Map.of("enabled", true),
                                        "betaUI", Map.of("enabled", false)))));

        BindContext ctx = new BindContext("test");
        AppConfig cfg = new AppConfigBinder().bind(src.load(), ctx);

        assertThat(ctx.hasErrors()).isFalse();
        assertThat(cfg.db()).isEqualTo(new DbConfig("jdbc:primary", 20));
        assertThat(cfg.readReplica()).contains(new DbConfig("jdbc:replica", 10)); // default max
        assertThat(cfg.endpoints()).containsExactly(new Endpoint("a.local", 8080), new Endpoint("b.local", 8081));
        assertThat(cfg.flags())
                .containsEntry("darkMode", new FeatureFlag(true))
                .containsEntry("betaUI", new FeatureFlag(false));
    }

    @Test
    void missing_required_field_in_nested_record_anchors_to_full_path() {
        ConfigSource src = ConfigSources.fromMap(Map.of(
                "app",
                Map.of(
                        "db", Map.of(/* url missing */ "max", 5),
                        "endpoints", List.of(),
                        "flags", Map.of())));

        BindContext ctx = new BindContext("test");
        new AppConfigBinder().bind(src.load(), ctx);

        assertThat(ctx.hasErrors()).isTrue();
        // Path-anchored: outer requireScalar prefixes "app.db", inner throws "DbConfig
        // missing required field 'url'", so the full message includes both.
        assertThat(ctx.errors().get(0).message()).contains("app.db").contains("missing required field 'url'");
    }

    @Test
    void unknown_key_in_nested_record_anchors_to_full_path() {
        ConfigSource src = ConfigSources.fromMap(Map.of(
                "app",
                Map.of(
                        "db", Map.of("url", "x", "wrng", "typo"),
                        "endpoints", List.of(),
                        "flags", Map.of())));

        BindContext ctx = new BindContext("test");
        new AppConfigBinder().bind(src.load(), ctx);

        assertThat(ctx.hasErrors()).isTrue();
        assertThat(ctx.errors().get(0).message()).contains("app.db").contains("unknown key 'wrng'");
    }

    @Test
    void optional_nested_record_returns_empty_when_absent() {
        ConfigSource src = ConfigSources.fromMap(Map.of(
                "app",
                Map.of(
                        "db", Map.of("url", "x"),
                        "endpoints", List.of(),
                        "flags", Map.of())));

        BindContext ctx = new BindContext("test");
        AppConfig cfg = new AppConfigBinder().bind(src.load(), ctx);

        assertThat(ctx.hasErrors()).isFalse();
        assertThat(cfg.readReplica()).isEmpty();
    }
}
