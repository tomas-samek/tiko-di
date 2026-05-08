package io.tiko.event.local;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultErrorHandlerTest {

    private static final String LOGGER_NAME = "io.tiko.events";

    private final List<LogRecord> captured = new ArrayList<>();
    private final SimpleFormatter formatter = new SimpleFormatter();
    private Handler sink;
    private Logger logger;
    private boolean originalUseParentHandlers;

    @BeforeEach
    void attachSink() {
        logger = Logger.getLogger(LOGGER_NAME);
        originalUseParentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        sink = new Handler() {
            @Override public void publish(LogRecord record) { captured.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        sink.setLevel(Level.ALL);
        logger.addHandler(sink);
    }

    @AfterEach
    void detachSink() {
        logger.removeHandler(sink);
        logger.setUseParentHandlers(originalUseParentHandlers);
    }

    @Test
    void logs_warning_with_class_method_event_type_and_message() {
        ErrorHandler handler = new DefaultErrorHandler();
        EventHandlerInfo info = new EventHandlerInfo(
            FakeService.class, "onSomething", FakeEvent.class, false);
        IllegalStateException cause = new IllegalStateException("boom");
        ErrorContext ctx = new EventHandlerError(info, new FakeEvent(), cause);

        handler.onError(ctx);

        assertThat(captured).hasSize(1);
        LogRecord record = captured.get(0);
        assertThat(record.getLevel()).isEqualTo(Level.WARNING);
        assertThat(record.getThrown()).isSameAs(cause);

        String rendered = formatter.formatMessage(record);
        assertThat(rendered).contains(FakeService.class.getName());
        assertThat(rendered).contains("onSomething");
        assertThat(rendered).contains(FakeEvent.class.getName());
        assertThat(rendered).contains("boom");
    }

    static class FakeService {}
    record FakeEvent() {}
}
