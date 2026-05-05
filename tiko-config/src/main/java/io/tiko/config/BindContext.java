package io.tiko.config;

import io.tiko.config.internal.ConfigError;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-bind-call accumulator. Generated binders call into this for required-field
 * resolution, default substitution, type coercion, and unknown-key checking.
 * Errors reported here are aggregated and thrown in one batch at end of validation.
 */
public final class BindContext {

    private final String source;
    private final List<ConfigError> errors = new ArrayList<>();

    public BindContext(String source) {
        this.source = source;
    }

    /** Reports an error against a known YAML location. */
    public void reportAt(int line, int column, String message) {
        errors.add(ConfigError.at(source, line, column, message));
    }

    /** Reports an error with no specific location. */
    public void report(String message) {
        errors.add(ConfigError.unanchored(message));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<ConfigError> errors() {
        return List.copyOf(errors);
    }

    public String source() {
        return source;
    }
}
