package io.tiko;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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

        TikoOptions options = TikoOptions.builder()
            .errorHandler(handler)
            .build();

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
}
