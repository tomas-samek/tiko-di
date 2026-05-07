package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.Tiko;
import io.tiko.TikoOptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EventErrorIsolationIntegrationTest {

    @Test
    void throwing_handler_routes_to_custom_error_handler() {
        AtomicReference<ErrorContext> captured = new AtomicReference<>();
        ErrorHandler recording = captured::set;

        TikoOptions opts = TikoOptions.builder().errorHandler(recording).build();
        try (Container container = Tiko.create(opts)) {
            assertThatCode(() -> container.getEventBus().publish(new Ping()))
                .doesNotThrowAnyException();
        }

        assertThat(captured.get()).isInstanceOf(EventHandlerError.class);
        EventHandlerError err = (EventHandlerError) captured.get();
        assertThat(err.handler().declaringClass()).isEqualTo(ThrowingHandler.class);
        assertThat(err.handler().methodName()).isEqualTo("onPing");
        assertThat(err.handler().eventType()).isEqualTo(Ping.class);
        assertThat(err.handler().async()).isFalse();
        assertThat(err.event()).isInstanceOf(Ping.class);
        assertThat(err.cause()).isInstanceOf(IllegalStateException.class);
        assertThat(err.cause()).hasMessage("integration boom");
    }
}
