package io.tiko.examples.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigIssueCode;
import io.tiko.ConfigurationFailure;
import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link ConfigurationFailure} routing contract:
 *
 * <ul>
 *   <li>When {@code @Configuration} binding accumulates errors, the framework calls
 *       {@link io.tiko.ErrorHandler#onError(ErrorContext)} with a single
 *       {@link ConfigurationFailure} carrying every {@link io.tiko.ConfigIssue} from
 *       this binding pass — <em>before</em> rethrowing.</li>
 *   <li>The original {@link ConfigValidationException} still propagates out of
 *       {@code Tiko.create(...)} (hard-fail preserved).</li>
 * </ul>
 */
class ConfigurationFailureRoutingTest {

    @Test
    void missingRequiredKeyEmitsConfigurationFailureAndRethrows() {
        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        // DbConfig requires `db.url` — provide a db section without it.
        var source = ConfigSources.fromMap(Map.of("db", Map.of("maxConnections", 5)));
        var opts = TikoOptions.builder()
                .errorHandler(recorded::add)
                .configSource(source)
                .build();

        assertThatThrownBy(() -> {
                    try (Container c = Tiko.create(opts)) {
                        // Unreachable.
                    }
                })
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("db.url is required but missing");

        assertThat(recorded).singleElement().isInstanceOfSatisfying(ConfigurationFailure.class, f -> {
            assertThat(f.cause()).isInstanceOf(ConfigValidationException.class);
            assertThat(f.issues())
                    .anyMatch(i -> i.code() == ConfigIssueCode.MISSING_KEY
                            && i.description().contains("db.url"));
        });
    }

    @Test
    void unknownSectionAndKeyAreCarriedAsDistinctCodes() {
        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        // Provide every required key so binding proceeds far enough to detect unknowns.
        var source = ConfigSources.fromMap(Map.of(
                "db", Map.of("url", "jdbc:test", "junkKey", "x"),
                "totallyUnknown", Map.of("foo", "bar")));
        var opts = TikoOptions.builder()
                .errorHandler(recorded::add)
                .configSource(source)
                .build();

        assertThatThrownBy(() -> {
                    try (Container c = Tiko.create(opts)) {
                        // Unreachable.
                    }
                })
                .isInstanceOf(ConfigValidationException.class);

        assertThat(recorded).singleElement().isInstanceOfSatisfying(ConfigurationFailure.class, f -> {
            assertThat(f.issues())
                    .extracting("code")
                    .contains(ConfigIssueCode.UNKNOWN_SECTION, ConfigIssueCode.UNKNOWN_KEY);
        });
    }
}
