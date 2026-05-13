package io.tiko.config.internal;

import io.tiko.ConfigIssueCode;

/**
 * One accumulated validation failure. The {@code source}/{@code line}/{@code column}
 * fields anchor the failure to a YAML location for error reporting. The {@code code}
 * is the stable category surfaced to user observability code via {@code ConfigIssue}.
 */
public record ConfigError(ConfigIssueCode code, String source, int line, int column, String message) {

    public static ConfigError at(ConfigIssueCode code, String source, int line, int column, String message) {
        return new ConfigError(code, source, line, column, message);
    }

    public static ConfigError unanchored(ConfigIssueCode code, String message) {
        return new ConfigError(code, null, -1, -1, message);
    }
}
