package io.tiko.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ConfigIssueCode;
import io.tiko.SourceLocation;
import io.tiko.config.internal.coercers.Coercers;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link BindContext}'s read methods emit anchored issues
 * when a location is known for the failing path, and fall back to
 * unanchored output when not.
 */
class BindContextLocationTest {

    @Test
    void missingRequiredScalarAnchoredToParentSectionLocation() {
        Map<String, SourceLocation> locations = new LinkedHashMap<>();
        locations.put("db", new SourceLocation("test.yaml", 1, 1)); // section header

        var ctx = new BindContext("test.yaml", locations);
        Map<String, Object> dbSection = new LinkedHashMap<>();
        ctx.requireScalar(dbSection, "password", "db.password", Coercers.intCoercer(), 0);

        assertThat(ctx.hasErrors()).isTrue();
        var issue = ctx.issues().get(0);
        assertThat(issue.code()).isEqualTo(ConfigIssueCode.MISSING_KEY);
        assertThat(issue.description()).startsWith("test.yaml:1:1 ");
    }

    @Test
    void unanchoredFallbackWhenLocationsMapIsEmpty() {
        var ctx = new BindContext("test.yaml", Map.of());
        Map<String, Object> dbSection = new LinkedHashMap<>();
        ctx.requireScalar(dbSection, "password", "db.password", Coercers.intCoercer(), 0);

        assertThat(ctx.hasErrors()).isTrue();
        var issue = ctx.issues().get(0);
        assertThat(issue.description()).doesNotContain("test.yaml:");
        assertThat(issue.description()).contains("db.password is required but missing");
    }

    @Test
    void coercionFailureAnchoredToValueLocation() {
        Map<String, SourceLocation> locations = new LinkedHashMap<>();
        locations.put("app.port", new SourceLocation("test.yaml", 5, 9));

        var ctx = new BindContext("test.yaml", locations);
        Map<String, Object> appSection = new LinkedHashMap<>();
        appSection.put("port", "eighty");
        ctx.requireScalar(appSection, "port", "app.port", Coercers.intCoercer(), 0);

        assertThat(ctx.hasErrors()).isTrue();
        var issue = ctx.issues().get(0);
        assertThat(issue.code()).isEqualTo(ConfigIssueCode.INVALID_VALUE);
        assertThat(issue.description()).startsWith("test.yaml:5:9 ");
    }
}
