package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultErrorHandlerTest {

    @BeforeEach
    void clearCapturedRecords() {
        CapturingLoggerFinder.clear();
    }

    @Test
    void logs_warning_with_class_method_event_type_and_message() {
        ErrorHandler handler = new DefaultErrorHandler();
        EventHandlerInfo info = new EventHandlerInfo(FakeService.class, "onSomething", FakeEvent.class, false);
        IllegalStateException cause = new IllegalStateException("boom");
        ErrorContext ctx = new EventHandlerError(info, new FakeEvent(), cause);

        handler.onError(ctx);

        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> "io.tiko.events".equals(r.loggerName()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.level()).isEqualTo(System.Logger.Level.WARNING);
                    assertThat(record.thrown()).isSameAs(cause);
                    assertThat(record.message()).contains(FakeService.class.getName());
                    assertThat(record.message()).contains("onSomething");
                    assertThat(record.message()).contains(FakeEvent.class.getName());
                    assertThat(record.message()).contains("boom");
                });
    }

    static class FakeService {}

    record FakeEvent() {}
}
