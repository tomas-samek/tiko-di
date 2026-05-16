package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.PostConstructFailure;
import io.tiko.ProduceFailure;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.sql.SQLException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage for Issue #97: both {@code @PostConstruct} and {@code @Produces}
 * may declare checked exceptions. The processor's widened catch publishes the
 * appropriate {@link ErrorContext} via the configured {@code ErrorHandler}, and
 * sneaky-throws the original throwable so the consumer of {@code container.get(...)}
 * sees the user's exception with type and stack trace intact.
 */
class CheckedExceptionPropagationTest {

    @Test
    void postConstructCheckedThrowRoutedAndPropagatedWithIdentityPreserved() {
        SQLException original = new SQLException("postconstruct-checked-identity");
        ThrowingCheckedPostConstructBean.thrownInstance = original;

        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder().errorHandler(recorded::add).build();

        Throwable thrown = null;
        try (Container container = Tiko.create(opts)) {
            try {
                container.runInRequestScope(() -> container.get(ThrowingCheckedPostConstructBean.class));
            } catch (Throwable t) {
                thrown = t;
            }
        }

        assertThat(thrown).as("identity preserved through sneakyThrow").isSameAs(original);
        assertThat(recorded).singleElement().isInstanceOfSatisfying(PostConstructFailure.class, f -> {
            assertThat(f.component()).isEqualTo(ThrowingCheckedPostConstructBean.class);
            assertThat(f.cause()).isSameAs(original);
        });
    }

    @Test
    void producesCheckedThrowRoutedAndPropagatedWithIdentityPreserved() {
        SQLException original = new SQLException("produces-checked-identity");
        ThrowingCheckedProducesFactory.thrownInstance = original;

        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder().errorHandler(recorded::add).build();

        Throwable thrown = null;
        try (Container container = Tiko.create(opts)) {
            try {
                container.runInRequestScope(() -> container.get(CheckedProducesOutput.class));
            } catch (Throwable t) {
                thrown = t;
            }
        }

        assertThat(thrown).as("identity preserved through sneakyThrow").isSameAs(original);
        assertThat(recorded).singleElement().isInstanceOfSatisfying(ProduceFailure.class, f -> {
            assertThat(f.declaringClass()).isEqualTo(ThrowingCheckedProducesFactory.class);
            assertThat(f.methodName()).isEqualTo("failingResource");
            assertThat(f.cause()).isSameAs(original);
        });
    }
}
