package io.tiko.processor.core;

/** Formats throwables for annotation-processor diagnostics. */
public final class StackTraces {

    private StackTraces() {}

    /**
     * Formats {@code t} as its class and message, one {@code \n  at <frame>} line per stack
     * frame, then the cause chain as {@code \nCaused by: ...}.
     */
    public static String format(Throwable t) {
        var sb = new StringBuilder();
        sb.append(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        for (var frame : t.getStackTrace()) {
            sb.append("\n  at ").append(frame);
        }
        var cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append("\nCaused by: ").append(format(cause));
        }
        return sb.toString();
    }
}
