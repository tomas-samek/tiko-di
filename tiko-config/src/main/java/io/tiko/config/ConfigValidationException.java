package io.tiko.config;

import io.tiko.config.internal.ConfigError;
import io.tiko.config.internal.ErrorReporter;

import java.util.List;

/**
 * Thrown once at container startup if the loaded configuration fails validation.
 * The exception message is the entire numbered report — anchored to YAML
 * file:line:col where applicable.
 */
public final class ConfigValidationException extends RuntimeException {

    // transient because ConfigError is intentionally an internal, non-Serializable type;
    // the formatted message in super(...) survives serialization regardless.
    private final transient List<ConfigError> errors;

    public ConfigValidationException(String source, List<ConfigError> errors) {
        super(ErrorReporter.format(source, errors));
        this.errors = List.copyOf(errors);
    }

    /** The raw error list, in the order they were accumulated. */
    public List<ConfigError> errors() {
        return errors == null ? List.of() : errors;
    }
}
