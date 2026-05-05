package io.tiko.config.internal;

/**
 * One accumulated validation failure. The {@code source}/{@code line}/{@code column}
 * fields anchor the failure to a YAML location for error reporting.
 */
public record ConfigError(String source, int line, int column, String message) {

    public static ConfigError at(String source, int line, int column, String message) {
        return new ConfigError(source, line, column, message);
    }

    public static ConfigError unanchored(String message) {
        return new ConfigError(null, -1, -1, message);
    }
}
