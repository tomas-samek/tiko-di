package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.AutoCloseFailure;
import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.PostConstructFailure;
import io.tiko.PreDestroyFailure;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the lifecycle-error routing contract:
 *
 * <ul>
 *   <li>{@code @PostConstruct} failures emit {@link PostConstructFailure} via the configured
 *       {@code ErrorHandler}, then the original throwable continues to propagate
 *       (hard-fail preserved).</li>
 *   <li>{@code @PreDestroy} failures emit {@link PreDestroyFailure} via the configured
 *       {@code ErrorHandler}; teardown of sibling beans still completes (log-and-continue
 *       preserved).</li>
 *   <li>{@code AutoCloseable.close()} failures on beans without a {@code @PreDestroy} emit
 *       {@link AutoCloseFailure}; teardown continues just like {@code @PreDestroy}.</li>
 * </ul>
 */
class LifecycleErrorRoutingTest {

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void throwingPostConstructEmitsPostConstructFailureAndRethrows() {
        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder().errorHandler(recorded::add).build();

        try (Container container = Tiko.create(opts)) {
            assertThatThrownBy(() ->
                            container.runInEventScope(() -> container.get(ThrowingPostConstructRequestBean.class)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("intentional-postconstruct");
        }

        assertThat(recorded).singleElement().isInstanceOfSatisfying(PostConstructFailure.class, f -> {
            assertThat(f.component()).isEqualTo(ThrowingPostConstructRequestBean.class);
            assertThat(f.cause()).isInstanceOf(IllegalStateException.class).hasMessage("intentional-postconstruct");
        });
    }

    @Test
    void throwingPreDestroyEmitsPreDestroyFailureAndContinuesTeardown() {
        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder().errorHandler(recorded::add).build();

        Container container = Tiko.create(opts);
        try {
            container.runInEventScope(() -> {
                container.get(LifoRequestA.class);
                container.get(ThrowingPreDestroyRequestBean.class);
            });
        } finally {
            container.shutdown();
        }

        // Teardown continued past the throw — sibling beans still fired.
        assertThat(TeardownRecorder.order)
                .as("Throwing @PreDestroy must not skip sibling beans")
                .contains("RequestA", "RequestB", "RequestC", "Throwing.boom");

        // The throw routed through ErrorHandler.
        assertThat(recorded).hasSize(1).first().isInstanceOfSatisfying(PreDestroyFailure.class, f -> {
            assertThat(f.component()).isEqualTo(ThrowingPreDestroyRequestBean.class);
            assertThat(f.cause()).isInstanceOf(IllegalStateException.class).hasMessage("intentional");
        });
    }

    @Test
    void throwingCloseEmitsAutoCloseFailureAndContinuesTeardown() {
        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder().errorHandler(recorded::add).build();

        Container container = Tiko.create(opts);
        try {
            container.runInEventScope(() -> {
                container.get(LifoRequestA.class);
                container.get(ThrowingCloseRequestBean.class);
            });
        } finally {
            container.shutdown();
        }

        // Teardown continued past the throw.
        assertThat(TeardownRecorder.order)
                .as("Throwing AutoCloseable.close() must not skip sibling beans")
                .contains("RequestA", "RequestB", "RequestC", "ThrowingClose.close");

        // Routed as AutoCloseFailure (NOT PreDestroyFailure).
        assertThat(recorded).hasSize(1).first().isInstanceOfSatisfying(AutoCloseFailure.class, f -> {
            assertThat(f.component()).isEqualTo(ThrowingCloseRequestBean.class);
            assertThat(f.cause()).isInstanceOf(IllegalStateException.class).hasMessage("intentional-close");
        });
    }
}
