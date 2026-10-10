package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.NoActiveEventScopeException;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An ErrorHandler that throws while handling a failed EVENT-scope teardown hook must not leave
 * the unit open on the thread: the ErrorHandler contract says such an exception is caught by the
 * framework. The next unit must open, get fresh beans, and nothing of the finished unit may stay
 * resolvable outside a unit.
 */
class TeardownErrorHandlerFailureTest {

    static Stream<Arguments> throwingHooks() {
        return Stream.of(
                Arguments.of("@PreDestroy", ThrowingPreDestroyEventBean.class),
                Arguments.of("AutoCloseable.close()", ThrowingCloseEventBean.class));
    }

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("throwingHooks")
    void aThrowingErrorHandlerDoesNotLeaveTheUnitOpen(String hook, Class<?> throwingBean) {
        var opts = TikoOptions.builder()
                .errorHandler(ctx -> {
                    throw new IllegalStateException("error handler failed");
                })
                .build();
        Container container = Tiko.create(opts);
        try {
            LifoEventA[] first = new LifoEventA[1];
            assertThatCode(() -> container.runInEventScope(() -> {
                        first[0] = container.get(LifoEventA.class);
                        container.get(throwingBean);
                    }))
                    .as("the handler's own exception is caught, as the ErrorHandler contract says")
                    .doesNotThrowAnyException();

            assertThat(TeardownRecorder.order)
                    .as("the rest of the unit was still torn down")
                    .contains("EventA", "EventB", "EventC");
            assertThatThrownBy(() -> container.get(LifoEventA.class))
                    .as("no bean of the finished unit stays resolvable outside a unit")
                    .isInstanceOf(NoActiveEventScopeException.class);

            LifoEventA second = container.supplyInEventScope(() -> container.get(LifoEventA.class));
            assertThat(second).as("the next unit opens and gets a fresh bean").isNotSameAs(first[0]);
        } finally {
            container.shutdown();
        }
    }

    /** Even an {@link Error} escaping the ErrorHandler propagates only after the unit is closed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("throwingHooks")
    void anErrorFromTheErrorHandlerStillClosesTheUnit(String hook, Class<?> throwingBean) {
        var opts = TikoOptions.builder()
                .errorHandler(ctx -> {
                    throw new AssertionError("error handler failed");
                })
                .build();
        Container container = Tiko.create(opts);
        try {
            assertThatThrownBy(() -> container.runInEventScope(() -> container.get(throwingBean)))
                    .isInstanceOf(AssertionError.class);

            assertThatThrownBy(() -> container.get(LifoEventA.class))
                    .as("the unit is closed despite the Error")
                    .isInstanceOf(NoActiveEventScopeException.class);
            assertThatCode(() -> container.runInEventScope(() -> container.get(LifoEventA.class)))
                    .as("the next unit opens")
                    .doesNotThrowAnyException();
        } finally {
            container.shutdown();
        }
    }
}
