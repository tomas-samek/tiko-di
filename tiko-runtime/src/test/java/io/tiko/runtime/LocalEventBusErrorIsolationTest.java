package io.tiko.runtime;

import io.tiko.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LocalEventBusErrorIsolationTest {

    private final ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void redirectStderr() {
        originalErr = System.err;
        System.setErr(new PrintStream(errCapture, true));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
    }

    @Test
    void throwing_handler_does_not_abort_subsequent_handlers() {
        EventBus bus = new LocalEventBus();
        AtomicInteger secondHandlerInvocations = new AtomicInteger();

        bus.subscribe(String.class, e -> { throw new IllegalStateException("first"); });
        bus.subscribe(String.class, e -> secondHandlerInvocations.incrementAndGet());

        bus.publish("hello");

        assertThat(secondHandlerInvocations).hasValue(1);
    }

    @Test
    void throwing_handler_does_not_propagate_to_publisher() {
        EventBus bus = new LocalEventBus();
        bus.subscribe(String.class, e -> { throw new IllegalStateException("kaboom"); });

        assertThatCode(() -> bus.publish("hello")).doesNotThrowAnyException();
    }

    @Test
    void throwing_programmatic_subscriber_logs_warn_with_event_type() {
        EventBus bus = new LocalEventBus();
        bus.subscribe(String.class, e -> { throw new IllegalStateException("logged"); });

        bus.publish("hello");

        String output = errCapture.toString();
        assertThat(output).contains("WARN");
        assertThat(output).contains("Programmatic event callback threw");
        assertThat(output).contains("java.lang.String");
        assertThat(output).contains("logged");
    }
}
