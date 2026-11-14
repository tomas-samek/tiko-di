package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.EventBus;
import io.tiko.EventQueueOverflowException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalEventBusErrorIsolationTest {

    @BeforeEach
    void clearCapturedRecords() {
        CapturingLoggerFinder.clear();
    }

    @Test
    void throwing_handler_does_not_abort_subsequent_handlers() {
        EventBus bus = new LocalEventBus();
        AtomicInteger secondHandlerInvocations = new AtomicInteger();

        bus.subscribe(String.class, e -> {
            throw new IllegalStateException("first");
        });
        bus.subscribe(String.class, e -> secondHandlerInvocations.incrementAndGet());

        bus.publish("hello");

        assertThat(secondHandlerInvocations).hasValue(1);
    }

    @Test
    void throwing_handler_does_not_propagate_to_publisher() {
        EventBus bus = new LocalEventBus();
        bus.subscribe(String.class, e -> {
            throw new IllegalStateException("kaboom");
        });

        assertThatCode(() -> bus.publish("hello")).doesNotThrowAnyException();
    }

    @Test
    void throwing_programmatic_subscriber_logs_warn_with_event_type() {
        EventBus bus = new LocalEventBus();
        IllegalStateException cause = new IllegalStateException("logged");
        bus.subscribe(String.class, e -> {
            throw cause;
        });

        bus.publish("hello");

        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> LocalEventBus.class.getName().equals(r.loggerName()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.level()).isEqualTo(System.Logger.Level.WARNING);
                    assertThat(record.thrown()).isSameAs(cause);
                    assertThat(record.message()).contains("Programmatic event callback threw");
                    assertThat(record.message()).contains("java.lang.String");
                    assertThat(record.message()).contains("logged");
                });
    }

    /** #714: the THROW overflow policy's exception is for the publisher; the other callbacks run first. */
    @Test
    void queueOverflowReachesThePublisherAfterTheOtherCallbacksRan() {
        EventBus bus = new LocalEventBus();
        AtomicInteger laterCallbackInvocations = new AtomicInteger();
        var overflow = new EventQueueOverflowException("queue full");

        bus.subscribe(String.class, e -> {
            throw overflow;
        });
        bus.subscribe(String.class, e -> laterCallbackInvocations.incrementAndGet());

        assertThatThrownBy(() -> bus.publish("hello")).isSameAs(overflow);
        assertThat(laterCallbackInvocations).hasValue(1);
    }
}
