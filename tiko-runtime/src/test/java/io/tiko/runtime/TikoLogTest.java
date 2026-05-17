package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for the package-private {@link TikoLog} helper. Lives in the same package so it
 * can see the package-private API directly without exposing the helper publicly.
 */
class TikoLogTest {

    @Test
    void short_circuit_when_level_filtered_does_not_format() {
        AtomicInteger toStringCalls = new AtomicInteger();
        Object expensiveArg = new Object() {
            @Override
            public String toString() {
                toStringCalls.incrementAndGet();
                return "expensive";
            }
        };
        RecordingLogger logger = new RecordingLogger(/* loggable= */ false);

        TikoLog.log(logger, Logger.Level.WARNING, "value is {0}", expensiveArg);

        assertThat(toStringCalls).hasValue(0);
        assertThat(logger.entries).isEmpty();
    }

    @Test
    void format_path_substitutes_parameters() {
        RecordingLogger logger = new RecordingLogger(/* loggable= */ true);

        TikoLog.log(logger, Logger.Level.WARNING, "value is {0}, count {1}", "x", 7);

        assertThat(logger.entries).hasSize(1);
        Entry entry = logger.entries.get(0);
        assertThat(entry.level).isEqualTo(Logger.Level.WARNING);
        assertThat(entry.message).isEqualTo("value is x, count 7");
        assertThat(entry.thrown).isNull();
    }

    @Test
    void throwable_variant_attaches_the_throwable() {
        RecordingLogger logger = new RecordingLogger(/* loggable= */ true);
        IllegalStateException boom = new IllegalStateException("kaboom");

        TikoLog.log(logger, Logger.Level.ERROR, boom, "failed to {0}", "X");

        assertThat(logger.entries).hasSize(1);
        Entry entry = logger.entries.get(0);
        assertThat(entry.level).isEqualTo(Logger.Level.ERROR);
        assertThat(entry.message).isEqualTo("failed to X");
        assertThat(entry.thrown).isSameAs(boom);
    }

    @Test
    void no_args_path_skips_format_step() {
        RecordingLogger logger = new RecordingLogger(/* loggable= */ true);

        TikoLog.log(logger, Logger.Level.INFO, "static message with no placeholders");

        assertThat(logger.entries).hasSize(1);
        assertThat(logger.entries.get(0).message).isEqualTo("static message with no placeholders");
    }

    private record Entry(Logger.Level level, String message, Throwable thrown) {}

    /** Captures log calls for inspection. */
    private static final class RecordingLogger implements Logger {
        private final boolean loggable;
        final List<Entry> entries = new ArrayList<>();

        RecordingLogger(boolean loggable) {
            this.loggable = loggable;
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return loggable;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            entries.add(new Entry(level, msg, thrown));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            // Our helper always calls the pre-formatted overload — this should never fire.
            throw new AssertionError("TikoLog should call the no-params overload, not this one");
        }
    }
}
