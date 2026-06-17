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
    void builderRoundTripsTeardownTimeout() {
        TikoOptions options =
                TikoOptions.builder().teardownTimeout(Duration.ofSeconds(5)).build();

        assertThat(options.teardownTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void teardownTimeoutDefaultIsNull() {
        // null = "unset" = unbounded inline teardown (today's behavior), zero overhead.
        assertThat(TikoOptions.builder().build().teardownTimeout()).isNull();
    }

    @Test
    void builderAcceptsZeroTeardownTimeout() {
        // ZERO is a valid degenerate config: give each hook no time, route a timeout immediately.
        TikoOptions options =
                TikoOptions.builder().teardownTimeout(Duration.ZERO).build();

        assertThat(options.teardownTimeout()).isEqualTo(Duration.ZERO);
    }

    @Test
    void builderRejectsNegativeTeardownTimeout() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> b.teardownTimeout(Duration.ofSeconds(-1)))
                .withMessageContaining("teardownTimeout");
    }

    @Test
    void builderRejectsNullTeardownTimeout() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.teardownTimeout(null));
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
    void queueCapacityAndOverflowDefaults() {
        TikoOptions opts = TikoOptions.builder().build();
        assertThat(opts.queueCapacity()).isEqualTo(1024);
        assertThat(opts.onOverflow()).isEqualTo(OverflowPolicy.CALLER_RUNS);
    }

    @Test
    void builderRoundTripsQueueCapacityAndOverflowPolicy() {
        TikoOptions opts = TikoOptions.builder()
                .queueCapacity(64)
                .onOverflow(OverflowPolicy.THROW)
                .build();
        assertThat(opts.queueCapacity()).isEqualTo(64);
        assertThat(opts.onOverflow()).isEqualTo(OverflowPolicy.THROW);
    }

    @Test
    void builderRejectsNonPositiveQueueCapacity() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> b.queueCapacity(0))
                .withMessageContaining("queueCapacity");
        assertThatIllegalArgumentException().isThrownBy(() -> b.queueCapacity(-1));
    }

    @Test
    void builderRejectsNullOverflowPolicy() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.onOverflow(null));
    }

    @Test
    void eventExecutorSizingDefaultsAreUnset() {
        // Unset = sentinel/null so the factory derives processor-based defaults (today's behaviour).
        TikoOptions opts = TikoOptions.builder().build();
        assertThat(opts.eventExecutorCoreSize()).isEqualTo(TikoOptions.UNSET_POOL_SIZE);
        assertThat(opts.eventExecutorMaxSize()).isEqualTo(TikoOptions.UNSET_POOL_SIZE);
        assertThat(opts.eventExecutorKeepAlive()).isNull();
    }

    @Test
    void builderRoundTripsEventExecutorSizing() {
        TikoOptions opts = TikoOptions.builder()
                .eventExecutorCoreSize(8)
                .eventExecutorMaxSize(32)
                .eventExecutorKeepAlive(Duration.ofSeconds(45))
                .build();
        assertThat(opts.eventExecutorCoreSize()).isEqualTo(8);
        assertThat(opts.eventExecutorMaxSize()).isEqualTo(32);
        assertThat(opts.eventExecutorKeepAlive()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void builderAcceptsZeroCoreSizeAndZeroKeepAlive() {
        // Both are valid ThreadPoolExecutor configs: a 0 core pool grows on demand; a 0 keep-alive
        // reaps idle non-core threads immediately.
        TikoOptions opts = TikoOptions.builder()
                .eventExecutorCoreSize(0)
                .eventExecutorKeepAlive(Duration.ZERO)
                .build();
        assertThat(opts.eventExecutorCoreSize()).isZero();
        assertThat(opts.eventExecutorKeepAlive()).isEqualTo(Duration.ZERO);
    }

    @Test
    void builderRejectsNegativeCoreSize() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> b.eventExecutorCoreSize(-1))
                .withMessageContaining("eventExecutorCoreSize");
    }

    @Test
    void builderRejectsNonPositiveMaxSize() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> b.eventExecutorMaxSize(0))
                .withMessageContaining("eventExecutorMaxSize");
        assertThatIllegalArgumentException().isThrownBy(() -> b.eventExecutorMaxSize(-1));
    }

    @Test
    void builderRejectsNegativeKeepAlive() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> b.eventExecutorKeepAlive(Duration.ofSeconds(-1)))
                .withMessageContaining("eventExecutorKeepAlive");
    }

    @Test
    void builderRejectsNullKeepAlive() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.eventExecutorKeepAlive(null));
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

    @Test
    void resolveOverrideReturnsRegisteredValue() {
        TikoOptions opts =
                TikoOptions.builder().override(String.class, () -> "override").build();
        assertThat(opts.resolveOverride(String.class, () -> "fallback")).isEqualTo("override");
    }

    @Test
    void resolveOverrideFallsBackWhenAbsent() {
        TikoOptions opts = TikoOptions.builder().build();
        assertThat(opts.resolveOverride(String.class, () -> "fallback")).isEqualTo("fallback");
    }

    @Test
    void resolveOverrideHonoursNamedKey() {
        TikoOptions opts = TikoOptions.builder()
                .override(String.class, "primary", () -> "primary-impl")
                .build();
        assertThat(opts.resolveOverride(String.class, "primary", () -> "fallback"))
                .isEqualTo("primary-impl");
        // unnamed and named are distinct keys — the unnamed lookup falls back
        assertThat(opts.resolveOverride(String.class, () -> "fallback")).isEqualTo("fallback");
    }

    @Test
    void resolveOverrideRejectsNullName() {
        TikoOptions opts = TikoOptions.builder().build();
        assertThatNullPointerException()
                .isThrownBy(() -> opts.resolveOverride(String.class, null, () -> "fallback"))
                .withMessageContaining("name");
    }

    @Test
    void internalAddOverrideAddsToImmutableOptions() {
        TikoOptions opts = TikoOptions.builder().build();
        java.util.function.Supplier<String> sup = () -> "shadowed";

        opts.internalAddOverride(String.class, sup);

        assertThat(opts.hasOverride(String.class)).isTrue();
        assertThat(opts.getOverride(String.class).get()).isEqualTo("shadowed");
    }

    @Test
    void internalAddOverrideIfAbsentRespectsExistingUserOverride() {
        java.util.function.Supplier<String> userSup = () -> "user-wins";
        java.util.function.Supplier<String> shadowSup = () -> "shadow-loses";
        TikoOptions opts = TikoOptions.builder().override(String.class, userSup).build();

        opts.internalAddOverrideIfAbsent(String.class, shadowSup);

        assertThat(opts.getOverride(String.class).get()).isEqualTo("user-wins");
    }

    @Test
    void internalAddOverrideIfAbsentRegistersWhenKeyMissing() {
        TikoOptions opts = TikoOptions.builder().build();
        java.util.function.Supplier<String> shadowSup = () -> "shadow";

        opts.internalAddOverrideIfAbsent(String.class, shadowSup);

        assertThat(opts.hasOverride(String.class)).isTrue();
        assertThat(opts.getOverride(String.class).get()).isEqualTo("shadow");
    }
}
