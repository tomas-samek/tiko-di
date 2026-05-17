package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.tiko.ErrorHandler;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TikoOptionsTest {

    @Test
    void builder_default_has_no_config_source_and_no_error_handler() {
        TikoOptions options = TikoOptions.builder().build();

        assertThat(options.configSource()).isNull();
        assertThat(options.errorHandler()).isNull();
    }

    @Test
    void builder_round_trips_error_handler() {
        ErrorHandler handler = ctx -> {};

        TikoOptions options = TikoOptions.builder().errorHandler(handler).build();

        assertThat(options.errorHandler()).isSameAs(handler);
    }

    @Test
    void builder_rejects_null_error_handler() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.errorHandler(null));
    }

    @Test
    void builder_rejects_null_config_source() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.configSource(null));
    }

    @Test
    void builder_round_trips_event_executor() {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            TikoOptions options = TikoOptions.builder().eventExecutor(executor).build();

            assertThat(options.eventExecutor()).isSameAs(executor);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void builder_rejects_null_event_executor() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.eventExecutor(null));
    }

    @Test
    void builder_event_executor_default_null() {
        TikoOptions options = TikoOptions.builder().build();
        assertThat(options.eventExecutor()).isNull();
    }

    @Test
    void builder_round_trips_shutdown_timeout() {
        TikoOptions options =
                TikoOptions.builder().shutdownTimeout(Duration.ofSeconds(2)).build();

        assertThat(options.shutdownTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void builder_shutdown_timeout_default_is_null() {
        TikoOptions options = TikoOptions.builder().build();

        // null means "not explicitly set" — matches the pattern for configSource/errorHandler/eventExecutor.
        // The 10s framework default is applied by Tiko.resolveShutdownTimeout, not by this field.
        assertThat(options.shutdownTimeout()).isNull();
    }

    @Test
    void builder_rejects_negative_shutdown_timeout() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> b.shutdownTimeout(Duration.ofSeconds(-1)))
                .withMessageContaining("shutdownTimeout");
    }

    @Test
    void builder_rejects_null_shutdown_timeout() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.shutdownTimeout(null));
    }
}
