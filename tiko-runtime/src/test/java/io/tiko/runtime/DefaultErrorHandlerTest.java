package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.AutoCloseFailure;
import io.tiko.ConfigIssue;
import io.tiko.ConfigIssueCode;
import io.tiko.ConfigurationFailure;
import io.tiko.ErrorHandler;
import io.tiko.EventDispatchRejected;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import io.tiko.PostConstructFailure;
import io.tiko.PreDestroyFailure;
import io.tiko.ProduceFailure;
import io.tiko.TransportError;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultErrorHandlerTest {

    private static final ErrorHandler HANDLER = new DefaultErrorHandler();

    @BeforeEach
    void clearCapturedRecords() {
        CapturingLoggerFinder.clear();
    }

    @Test
    void eventHandlerErrorLogsClassMethodEventTypeAndMessage() {
        EventHandlerInfo info = new EventHandlerInfo(FakeService.class, "onSomething", FakeEvent.class, false);
        IllegalStateException cause = new IllegalStateException("boom");

        HANDLER.onError(new EventHandlerError(info, new FakeEvent(), cause));

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

    @Test
    void eventDispatchRejectedLogsEventTypeAtWarning() {
        HANDLER.onError(new EventDispatchRejected(new FakeEvent()));

        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> "io.tiko.events".equals(r.loggerName()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.level()).isEqualTo(System.Logger.Level.WARNING);
                    assertThat(record.message()).contains(FakeEvent.class.getName());
                    assertThat(record.message()).contains("ROUTE_TO_DLQ");
                });
    }

    @Test
    void postConstructFailureLogsComponentAndCause() {
        RuntimeException cause = new RuntimeException("init-fail");

        HANDLER.onError(new PostConstructFailure(FakeService.class, cause));

        assertThat(CapturingLoggerFinder.RECORDS).singleElement().satisfies(r -> {
            assertThat(r.level()).isEqualTo(System.Logger.Level.WARNING);
            assertThat(r.thrown()).isSameAs(cause);
            assertThat(r.message()).contains("@PostConstruct").contains(FakeService.class.getName());
        });
    }

    @Test
    void preDestroyFailureLogsComponentAndCause() {
        RuntimeException cause = new RuntimeException("cleanup-fail");

        HANDLER.onError(new PreDestroyFailure(FakeService.class, cause));

        assertThat(CapturingLoggerFinder.RECORDS).singleElement().satisfies(r -> {
            assertThat(r.thrown()).isSameAs(cause);
            assertThat(r.message()).contains("@PreDestroy").contains(FakeService.class.getName());
        });
    }

    @Test
    void autoCloseFailureLogsComponentAndCause() {
        RuntimeException cause = new RuntimeException("close-fail");

        HANDLER.onError(new AutoCloseFailure(FakeService.class, cause));

        assertThat(CapturingLoggerFinder.RECORDS).singleElement().satisfies(r -> {
            assertThat(r.thrown()).isSameAs(cause);
            assertThat(r.message()).contains("AutoCloseable.close()").contains(FakeService.class.getName());
        });
    }

    @Test
    void configurationFailureLogsOneLinePerIssue() {
        List<ConfigIssue> issues = List.of(
                new ConfigIssue(ConfigIssueCode.MISSING_KEY, "field 'db.url' missing"),
                new ConfigIssue(ConfigIssueCode.INVALID_VALUE, "field 'db.port' must be int"));

        HANDLER.onError(new ConfigurationFailure(issues, null));

        assertThat(CapturingLoggerFinder.RECORDS).hasSize(2);
        assertThat(CapturingLoggerFinder.RECORDS.get(0).message())
                .contains("MISSING_KEY")
                .contains("db.url");
        assertThat(CapturingLoggerFinder.RECORDS.get(1).message())
                .contains("INVALID_VALUE")
                .contains("db.port");
    }

    @Test
    void produceFailureLogsClassMethodAndCause() {
        RuntimeException cause = new RuntimeException("factory-fail");

        HANDLER.onError(new ProduceFailure(FakeService.class, "mkBean", cause));

        assertThat(CapturingLoggerFinder.RECORDS).singleElement().satisfies(r -> {
            assertThat(r.thrown()).isSameAs(cause);
            assertThat(r.message())
                    .contains("@Produces")
                    .contains(FakeService.class.getName())
                    .contains("mkBean");
        });
    }

    @Test
    void transportErrorLogsTransportNameAndCause() {
        RuntimeException cause = new RuntimeException("kafka-fail");
        TransportError err = new FakeTransportError("kafka", cause);

        HANDLER.onError(err);

        assertThat(CapturingLoggerFinder.RECORDS).singleElement().satisfies(r -> {
            assertThat(r.thrown()).isSameAs(cause);
            assertThat(r.message()).contains("Transport").contains("kafka");
        });
    }

    static class FakeService {}

    record FakeEvent() {}

    private record FakeTransportError(String transport, Throwable cause) implements TransportError {}
}
