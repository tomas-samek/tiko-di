package io.tiko.event.local;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class Slf4jWarnErrorHandlerTest {

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
    void logs_warn_with_class_method_event_type_and_message() {
        ErrorHandler handler = new Slf4jWarnErrorHandler();
        EventHandlerInfo info = new EventHandlerInfo(
            FakeService.class, "onSomething", FakeEvent.class, false);
        ErrorContext ctx = new EventHandlerError(info, new FakeEvent(), new IllegalStateException("boom"));

        handler.onError(ctx);

        String output = errCapture.toString();
        assertThat(output).contains("WARN");
        assertThat(output).contains("FakeService");
        assertThat(output).contains("onSomething");
        assertThat(output).contains("FakeEvent");
        assertThat(output).contains("boom");
    }

    static class FakeService {}
    record FakeEvent() {}
}
