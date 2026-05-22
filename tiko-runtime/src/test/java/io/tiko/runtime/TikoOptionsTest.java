package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import java.time.Duration;
import java.util.function.UnaryOperator;
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

    @Test
    void eventBusDecoratorDefaultsToNull() {
        TikoOptions opts = TikoOptions.builder().build();
        assertThat(opts.eventBusDecorator()).isNull();
    }

    @Test
    void eventBusDecoratorStoresTheSuppliedFunction() {
        UnaryOperator<EventBus> deco = bus -> bus;
        TikoOptions opts = TikoOptions.builder().eventBusDecorator(deco).build();
        assertThat(opts.eventBusDecorator()).isSameAs(deco);
    }

    @Test
    void eventBusDecoratorRejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> TikoOptions.builder().eventBusDecorator(null))
                .withMessageContaining("eventBusDecorator");
    }

    @Test
    void overrideStoresTypeKeyedSupplier() {
        java.util.function.Supplier<String> sup = () -> "test";
        TikoOptions opts = TikoOptions.builder().override(String.class, sup).build();
        assertThat(opts.hasOverride(String.class)).isTrue();
        assertThat(opts.getOverride(String.class).get()).isEqualTo("test");
    }

    @Test
    void overrideStoresNamedKeyedSupplier() {
        java.util.function.Supplier<String> sup = () -> "primary-impl";
        TikoOptions opts =
                TikoOptions.builder().override(String.class, "primary", sup).build();
        assertThat(opts.hasOverride(String.class, "primary")).isTrue();
        assertThat(opts.hasOverride(String.class)).isFalse(); // unnamed and named are distinct keys
        assertThat(opts.getOverride(String.class, "primary").get()).isEqualTo("primary-impl");
    }

    @Test
    void overrideRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> TikoOptions.builder().override(null, () -> "x"));
        assertThatNullPointerException()
                .isThrownBy(
                        () -> TikoOptions.builder().override(String.class, (java.util.function.Supplier<String>) null));
    }

    @Test
    void noOverridesByDefault() {
        TikoOptions opts = TikoOptions.builder().build();
        assertThat(opts.hasOverride(String.class)).isFalse();
        assertThat(opts.hasOverride(String.class, "any")).isFalse();
    }
}
