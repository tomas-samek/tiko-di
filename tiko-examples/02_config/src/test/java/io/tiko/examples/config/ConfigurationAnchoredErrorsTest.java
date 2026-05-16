package io.tiko.examples.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigurationFailure;
import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * End-to-end verification of YAML source anchors: a misconfigured YAML produces
 * {@link ConfigurationFailure} issues whose descriptions are anchored to the
 * {@code line:column} where the offending value lives, AND the thrown
 * {@link ConfigValidationException} carries the same anchors.
 *
 * <p>The fixture ({@code bad-config.yaml}) triggers three distinct anchor paths:
 * <ul>
 *   <li>MISSING_KEY: {@code db.url} missing — anchored to the {@code db} section header (line 7).</li>
 *   <li>INVALID_VALUE: {@code app.server.port} is not an int — anchored to the {@code app.server}
 *       header (line 13) because nested-record coercion bubbles up through the parent
 *       {@code requireScalar(..., "app.server", ...)} call site.</li>
 *   <li>UNKNOWN_SECTION: top-level {@code garbage} — anchored to the section's own line (line 16).</li>
 * </ul>
 *
 * <p><strong>Note on the {@code config:} source prefix:</strong> the framework's
 * {@code BindContext} uses the bootstrap-time source label (currently {@code "config"},
 * hardcoded by {@link Tiko}) for every error's prefix, rather than the per-{@link
 * io.tiko.SourceLocation} {@code source()}. So descriptions are anchored as
 * {@code "config:LINE:COL ..."} regardless of which file the value actually came from.
 * That's a known follow-up — when fixed, this test will need to switch to
 * {@code "bad-config.yaml:"} substrings.</p>
 */
class ConfigurationAnchoredErrorsTest {

    @Test
    void missingRequiredKeyAndInvalidValueAndUnknownSectionAllAnchored() {
        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder()
                .errorHandler(recorded::add)
                .configSource(ConfigSources.classpath("bad-config.yaml"))
                .build();

        assertThatThrownBy(() -> {
                    try (Container c = Tiko.create(opts)) {
                        // Unreachable — binding errors must rethrow.
                    }
                })
                .isInstanceOf(ConfigValidationException.class);

        assertThat(recorded).singleElement().isInstanceOfSatisfying(ConfigurationFailure.class, f -> {
            var descriptions = f.issues().stream().map(i -> i.description()).toList();

            // Anchors — line numbers reflect the fixture layout (see bad-config.yaml).
            //   line  7: db:
            //   line 13:   server:        (parent of the bad port scalar)
            //   line 16: garbage:
            assertThat(descriptions)
                    .as("MISSING_KEY for db.url anchored to db section header (line 7)")
                    .anyMatch(d -> d.contains(":7:") && d.contains("db.url") && d.contains("is required but missing"));
            assertThat(descriptions)
                    .as("INVALID_VALUE for app.server.port bubbles up anchored to app.server header (line 13)")
                    .anyMatch(d -> d.contains(":13:") && d.contains("app.server") && d.contains("port"));
            assertThat(descriptions)
                    .as("UNKNOWN_SECTION for 'garbage' anchored to section line (line 16)")
                    .anyMatch(d ->
                            d.contains(":16:") && d.contains("unknown top-level section") && d.contains("garbage"));
        });
    }
}
