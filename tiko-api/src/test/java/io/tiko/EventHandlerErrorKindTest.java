package io.tiko;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EventHandlerErrorKindTest {

    private static final EventHandlerInfo INFO = new EventHandlerInfo(String.class, "onEvent", Object.class, true);

    static Stream<Arguments> kinds() {
        return Stream.of(
                Arguments.of(
                        "single failed attempt", new IllegalStateException("boom"), 1, DeliveryFailureKind.EXCEPTION),
                Arguments.of("retries exhausted", new IllegalStateException("boom"), 3, DeliveryFailureKind.EXHAUSTED),
                Arguments.of("timeout", new TimeoutException("slow"), 1, DeliveryFailureKind.TIMEOUT),
                Arguments.of(
                        "timeout wins over exhaustion", new TimeoutException("slow"), 4, DeliveryFailureKind.TIMEOUT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    void kindIsDerivedFromCauseAndAttempts(String name, Throwable cause, int attempts, DeliveryFailureKind expected) {
        EventHandlerError error = new EventHandlerError(INFO, new Object(), cause, attempts);
        assertThat(error.kind()).isEqualTo(expected);
    }

    @Test
    void singleArgConstructorIsExceptionKind() {
        EventHandlerError error = new EventHandlerError(INFO, new Object(), new RuntimeException());
        assertThat(error.attempts()).isEqualTo(1);
        assertThat(error.kind()).isEqualTo(DeliveryFailureKind.EXCEPTION);
    }
}
