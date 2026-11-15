package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.EventHandlerError;
import io.tiko.PostConstructFailure;
import io.tiko.ProduceFailure;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                container.runInEventScope(() -> container.get(ThrowingCheckedPostConstructBean.class));
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
                container.runInEventScope(() -> container.get(CheckedProducesOutput.class));
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

    /**
     * #713: a constructor may declare a checked exception. It reaches the caller unchanged, as an
     * unchecked constructor failure does, and is not routed: no {@link ErrorContext} describes a
     * constructor.
     */
    @Test
    void constructorCheckedThrowPropagatesWithIdentityPreserved() {
        SQLException original = new SQLException("constructor-checked-identity");
        ThrowingCheckedConstructorBean.thrownInstance = original;

        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder().errorHandler(recorded::add).build();

        Throwable thrown = null;
        try (Container container = Tiko.create(opts)) {
            try {
                container.runInEventScope(() -> container.get(ThrowingCheckedConstructorBean.class));
            } catch (Throwable t) {
                thrown = t;
            }
        } finally {
            ThrowingCheckedConstructorBean.thrownInstance = null;
        }

        assertThat(thrown).as("identity preserved through sneakyThrow").isSameAs(original);
        assertThat(recorded).isEmpty();
    }

    /** #713: a self-returning static {@code @Produces} factory is a {@code @Produces} method like any other. */
    @Test
    void selfFactoryCheckedThrowRoutedAndPropagatedWithIdentityPreserved() {
        IOException original = new IOException("self-factory-checked-identity");
        ThrowingCheckedSelfFactoryBean.thrownInstance = original;

        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder().errorHandler(recorded::add).build();

        Throwable thrown = null;
        try (Container container = Tiko.create(opts)) {
            try {
                container.runInEventScope(() -> container.get(ThrowingCheckedSelfFactoryBean.class));
            } catch (Throwable t) {
                thrown = t;
            }
        } finally {
            ThrowingCheckedSelfFactoryBean.thrownInstance = null;
        }

        assertThat(thrown).as("identity preserved through sneakyThrow").isSameAs(original);
        assertThat(recorded).singleElement().isInstanceOfSatisfying(ProduceFailure.class, f -> {
            assertThat(f.declaringClass()).isEqualTo(ThrowingCheckedSelfFactoryBean.class);
            assertThat(f.methodName()).isEqualTo("open");
            assertThat(f.cause()).isSameAs(original);
        });
    }

    @Test
    void componentsThatDeclareCheckedExceptionsAreBuiltWhenNothingIsThrown() {
        try (Container container = Tiko.create()) {
            container.runInEventScope(() -> {
                assertThat(container.get(ThrowingCheckedConstructorBean.class)).isNotNull();
                assertThat(container.get(ThrowingCheckedSelfFactoryBean.class)).isNotNull();
            });
        }
    }

    /** #746: an async handler may declare a checked exception; the ErrorHandler gets it as thrown. */
    @Test
    void asyncHandlerCheckedThrowReachesTheErrorHandlerUnwrapped() throws InterruptedException {
        IOException original = new IOException("async-handler-checked-identity");
        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var routed = new CountDownLatch(1);
        var opts = TikoOptions.builder()
                .errorHandler(context -> {
                    recorded.add(context);
                    routed.countDown();
                })
                .build();

        try (Container container = Tiko.create(opts)) {
            container.getEventBus().publish(new CheckedAsyncPing(original));

            assertThat(routed.await(5, TimeUnit.SECONDS))
                    .as("the handler's failure reached the ErrorHandler")
                    .isTrue();
        }

        assertThat(recorded)
                .singleElement()
                .isInstanceOfSatisfying(
                        EventHandlerError.class,
                        error -> assertThat(error.cause()).isSameAs(original));
    }

    /**
     * #746: a {@code @Produces} method whose parameter comes from a failing {@code @Produces} method
     * did not fail itself. The failure is routed once, for the method that threw.
     */
    @Test
    void failureOfAProducedParameterIsRoutedOnceForTheMethodThatThrew() {
        SQLException original = new SQLException("chained-produces-identity");
        ThrowingCheckedProducesFactory.thrownInstance = original;

        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder().errorHandler(recorded::add).build();

        Throwable thrown = null;
        try (Container container = Tiko.create(opts)) {
            try {
                container.runInEventScope(() -> container.get(ChainedProducesOutput.class));
            } catch (Throwable t) {
                thrown = t;
            }
        }

        assertThat(thrown).isSameAs(original);
        assertThat(recorded).singleElement().isInstanceOfSatisfying(ProduceFailure.class, f -> {
            assertThat(f.declaringClass()).isEqualTo(ThrowingCheckedProducesFactory.class);
            assertThat(f.methodName()).isEqualTo("failingResource");
        });
    }
}
