package io.tiko.config.internal;

import io.tiko.ConfigIssue;
import java.util.List;

/** Formats a list of {@link ConfigIssue}s into the user-facing report. */
public final class ErrorReporter {

    private ErrorReporter() {}

    public static String format(String source, List<ConfigIssue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append(issues.size()).append(" problem(s) in ").append(source).append(":\n");
        int idx = 1;
        for (ConfigIssue issue : issues) {
            sb.append("\n  ")
                    .append(idx++)
                    .append(". ")
                    .append(issue.description())
                    .append("\n");
        }
        return sb.toString();
    }
}
