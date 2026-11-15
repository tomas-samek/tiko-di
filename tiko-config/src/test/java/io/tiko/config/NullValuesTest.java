package io.tiko.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigIssueCode;
import io.tiko.SourceLocation;
import io.tiko.config.internal.DeepMerge;
import io.tiko.config.internal.YamlLoader;
import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CoercionException;
import io.tiko.config.internal.coercers.CompositeCoercers;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #759, #765: a key written without a value ({@code url:}, {@code url: ~}) is a YAML null. It means
 * "no value here": an optional key is absent, a required key is reported with its location, a higher
 * layer's empty key or section leaves the lower layer's value in place, and nothing throws
 * {@code NullPointerException}.
 */
class NullValuesTest {

    private static YamlLoader.LoadedYaml load(String yaml) {
        return YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "app.yaml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(YamlLoader.LoadedYaml loaded, String name) {
        return new LinkedHashMap<>((Map<String, Object>) loaded.data().get(name));
    }

    @Test
    void requiredKeyWithoutAValueIsReportedAtTheKeyAndNotBoundAsNull() {
        var loaded = load("db:\n  url:\n");
        var ctx = new BindContext("app.yaml", loaded.locations());
        var db = section(loaded, "db");

        String url = ctx.requireScalar(db, "url", "db.url", Coercers.stringCoercer(), "fallback");

        assertThat(url).isEqualTo("fallback");
        assertThat(ctx.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(ConfigIssueCode.MISSING_KEY);
            assertThat(issue.description()).contains("app.yaml:2:3", "db.url is required but has no value");
        });
        assertThat(db)
                .as("the key is consumed, so it is not reported as unknown too")
                .isEmpty();
    }

    @Test
    void optionalKeyWithoutAValueIsAbsent() {
        var loaded = load("db:\n  user: ~\n  pool:\n");
        var ctx = new BindContext("app.yaml", loaded.locations());
        var db = section(loaded, "db");

        Optional<String> user = ctx.optionalScalar(db, "user", "db.user", Coercers.stringCoercer());
        int pool = ctx.scalarOrDefault(db, "pool", "db.pool", Coercers.intCoercer(), 10);

        assertThat(user).isEmpty();
        assertThat(pool).isEqualTo(10);
        assertThat(ctx.hasErrors()).isFalse();
        assertThat(db).isEmpty();
    }

    @Test
    void emptyElementOfAListOrMapIsACoercionErrorNotANullPointerException() {
        var hosts = new ArrayList<Object>();
        hosts.add("a");
        hosts.add(null);
        var labels = new LinkedHashMap<String, Object>();
        labels.put("env", null);

        assertThatThrownBy(
                        () -> CompositeCoercers.list(Coercers.stringCoercer()).coerce(hosts))
                .isInstanceOf(CoercionException.class)
                .hasMessageContaining("element at index 1 has no value");
        assertThatThrownBy(() -> CompositeCoercers.set("db.hosts", Coercers.stringCoercer())
                        .coerce(hosts))
                .isInstanceOf(CoercionException.class)
                .hasMessageContaining("element at index 1 has no value");
        assertThatThrownBy(() -> CompositeCoercers.map(Coercers.stringCoercer()).coerce(labels))
                .isInstanceOf(CoercionException.class)
                .hasMessageContaining("entry 'env' has no value");
    }

    @Test
    void emptyKeyOrSectionInAHigherLayerLeavesTheLowerLayersValueInPlace() {
        var defaults = load("db:\n  url: jdbc:h2:mem\n  pool: 4\n").data();
        var emptySection = load("db:\n  # url: jdbc:postgresql://prod\n").data();
        var emptyKey = load("db:\n  url:\n  pool: 8\n").data();

        assertThat(DeepMerge.merge(defaults, emptySection))
                .isEqualTo(Map.of("db", Map.of("url", "jdbc:h2:mem", "pool", "4")));
        assertThat(DeepMerge.merge(defaults, emptyKey))
                .isEqualTo(Map.of("db", Map.of("url", "jdbc:h2:mem", "pool", "8")));
    }

    @Test
    void nestedRecordFieldsWithoutAValueFollowTheSameRule() {
        var node = new LinkedHashMap<String, Object>();
        node.put("host", null);
        node.put("port", null);
        node.put("alias", null);

        assertThatThrownBy(() -> io.tiko.config.internal.coercers.NestedRecordSupport.requireField(
                        node, "host", "ServerConfig", Coercers.stringCoercer()))
                .isInstanceOf(CoercionException.class)
                .hasMessage("ServerConfig.host is required but has no value");
        assertThat(io.tiko.config.internal.coercers.NestedRecordSupport.fieldOrDefault(
                        node, "port", "ServerConfig", Coercers.intCoercer(), 8080))
                .isEqualTo(8080);
        assertThat(io.tiko.config.internal.coercers.NestedRecordSupport.optionalField(
                        node, "alias", "ServerConfig", Coercers.stringCoercer()))
                .isEmpty();
        assertThat(node).isEmpty();
    }

    @Test
    void keyWithoutAValueKeepsItsLocation() {
        assertThat(load("db:\n  url:\n").locations()).containsEntry("db.url", new SourceLocation("app.yaml", 2, 3));
    }

    @Test
    void emptyElementsAreStillReadSoTheBinderCanReportThem() {
        assertThat(load("hosts:\n  - a\n  -\n").data().get("hosts")).isEqualTo(java.util.Arrays.asList("a", null));
    }
}
