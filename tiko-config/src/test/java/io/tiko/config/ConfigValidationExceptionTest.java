package io.tiko.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigIssue;
import io.tiko.ConfigIssueCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigValidationExceptionTest {

    @Test
    void formats_multiple_errors_as_numbered_report() {
        var issues = List.of(
                new ConfigIssue(ConfigIssueCode.MISSING_KEY, "config.yaml:5:7 db.url is required but missing"),
                new ConfigIssue(
                        ConfigIssueCode.INVALID_VALUE,
                        "config.yaml:6:18 db.maxConnections expected integer, got string \"ten\""));

        var ex = new ConfigValidationException("config.yaml", issues);

        assertThat(ex.getMessage())
                .contains("2 problem(s) in config.yaml")
                .contains("1. config.yaml:5:7")
                .contains("db.url is required but missing")
                .contains("2. config.yaml:6:18")
                .contains("db.maxConnections expected integer");
    }

    @Test
    void single_error_reports_singular_problem_count() {
        var issues = List.of(new ConfigIssue(ConfigIssueCode.INVALID_VALUE, "c.yaml:1:1 boom"));
        assertThatThrownBy(() -> {
                    throw new ConfigValidationException("c.yaml", issues);
                })
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("1 problem(s) in c.yaml");
    }
}
