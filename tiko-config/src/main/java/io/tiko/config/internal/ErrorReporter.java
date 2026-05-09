package io.tiko.config.internal;

import java.util.List;

/** Formats a list of {@link ConfigError}s into the user-facing report. */
public final class ErrorReporter {

    private ErrorReporter() {}

    public static String format(String source, List<ConfigError> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append(errors.size()).append(" problem(s) in ").append(source).append(":\n");
        int idx = 1;
        for (ConfigError e : errors) {
            sb.append("\n  ").append(idx++).append(". ");
            if (e.line() > 0) {
                sb.append(e.source())
                        .append(":")
                        .append(e.line())
                        .append(":")
                        .append(e.column())
                        .append("  ");
            }
            sb.append(e.message()).append("\n");
        }
        return sb.toString();
    }
}
