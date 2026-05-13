package io.tiko.config;

import io.tiko.ConfigIssue;
import io.tiko.config.internal.ErrorReporter;
import java.util.List;

/**
 * Thrown once at container startup if the loaded configuration fails validation.
 * The exception message is the entire numbered report — anchored to YAML
 * {@code file:line:col} where applicable.
 *
 * <p>The structured per-issue list is available via {@link #issues()}; observability code
 * that prefers programmatic dispatch on {@link io.tiko.ConfigIssueCode} should use that
 * rather than parsing the message.
 */
public final class ConfigValidationException extends RuntimeException {

    private final transient List<ConfigIssue> issues;

    public ConfigValidationException(String source, List<ConfigIssue> issues) {
        super(ErrorReporter.format(source, issues));
        this.issues = List.copyOf(issues);
    }

    /** The structured issue list, in the order accumulated. */
    public List<ConfigIssue> issues() {
        return issues == null ? List.of() : issues;
    }
}
