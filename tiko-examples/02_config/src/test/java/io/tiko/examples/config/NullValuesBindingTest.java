package io.tiko.examples.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigIssue;
import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

/**
 * #759: a key written without a value ({@code url:}, {@code url: ~}) through the generated binders.
 * A required key is reported with its location, an optional or defaulted one is simply absent, an
 * empty list element is reported, and nothing surfaces as a {@code NullPointerException}.
 */
class NullValuesBindingTest {

    @Test
    void keysWithoutAValueAreReportedOrAbsentNeverANullPointerException() {
        assertThatThrownBy(() -> {
                    try (Container c = Tiko.create(ConfigSources.classpath("null-values.yaml"))) {
                        // Unreachable: the required keys without a value fail the boot.
                    }
                })
                .isInstanceOfSatisfying(ConfigValidationException.class, e -> {
                    var descriptions =
                            e.issues().stream().map(ConfigIssue::description).toList();
                    assertThat(descriptions)
                            .as("a required key without a value, anchored at the key")
                            .anyMatch(d -> d.contains("null-values.yaml:3:3")
                                    && d.contains("db.url is required but has no value"));
                    assertThat(descriptions)
                            .as("the same inside a nested record")
                            .anyMatch(d -> d.contains("ServerConfig.host is required but has no value"));
                    assertThat(descriptions)
                            .as("an empty list element")
                            .anyMatch(d -> d.contains("allow.hosts") && d.contains("element at index 1 has no value"));
                    assertThat(descriptions)
                            .as("an Optional key and a defaulted key without a value are absent, not errors")
                            .noneMatch(d -> d.contains("connectTimeout") || d.contains("logLevel"));
                });
    }
}
