package io.tiko.runtime;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-scoped {@link System.LoggerFinder} installed via
 * {@code META-INF/services/java.lang.System$LoggerFinder} on the tiko-runtime test
 * classpath. Captures every {@code System.Logger} call into {@link #RECORDS} so tests
 * can assert on log output without poking at JUL handlers.
 *
 * <p>{@code LoggerFinder} is JVM-wide: once the JDK resolves this finder, every
 * {@code System.getLogger(name)} returns a {@link RecordingLogger} for the remainder
 * of the JVM. Tests that need clean assertions call {@link #clear()} in
 * {@code @BeforeEach}; tests that don't assert just ignore {@code RECORDS}.
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
            // Capture everything — tests filter by level after the fact.
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            RECORDS.add(new LogEntry(name, level, msg, thrown));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            // Pre-format with MessageFormat to match what the framework helper produces.
            String msg = (params == null || params.length == 0) ? format : MessageFormat.format(format, params);
            RECORDS.add(new LogEntry(name, level, msg, null));
        }
    }
}
