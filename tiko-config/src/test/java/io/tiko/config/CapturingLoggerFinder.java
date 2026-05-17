package io.tiko.config;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-scoped {@link System.LoggerFinder} for tiko-config tests. Same shape as the
 * tiko-runtime equivalent — package-private duplication is acceptable for v1 (no shared
 * test-support module).
 */
public final class CapturingLoggerFinder extends System.LoggerFinder {

    public static final List<LogEntry> RECORDS = new CopyOnWriteArrayList<>();

    public static void clear() {
        RECORDS.clear();
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new RecordingLogger(name);
    }

    public record LogEntry(String loggerName, System.Logger.Level level, String message, Throwable thrown) {}

    private static final class RecordingLogger implements System.Logger {
        private final String name;

        RecordingLogger(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            RECORDS.add(new LogEntry(name, level, msg, thrown));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            String msg = (params == null || params.length == 0) ? format : MessageFormat.format(format, params);
            RECORDS.add(new LogEntry(name, level, msg, null));
        }
    }
}
