package io.tiko.examples.basic.crossscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.EventHandlerError;
import io.tiko.NoActiveEventScopeException;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #731: an event that cannot be delivered because its handler's component cannot be resolved
 * reaches the {@code ErrorHandler} with the handler's identity, as a failure inside the handler does.
 */
class UndeliverableEventTest {

    @BeforeEach
    void clearReceived() {
        UnitListener.RECEIVED.clear();
    }

    @Test
    void eventForAnEventScopedHandlerPublishedOutsideAUnitIsRoutedToTheErrorHandler() {
        var routed = new CopyOnWriteArrayList<ErrorContext>();
        var options = TikoOptions.builder().errorHandler(routed::add).build();

        try (Container container = Tiko.create(options)) {
            container.getEventBus().publish(new UnitPing("outside"));
            container.runInEventScope(() -> container.getEventBus().publish(new UnitPing("inside")));
        }

        assertThat(UnitListener.RECEIVED).containsExactly("inside");
        assertThat(routed).singleElement().isInstanceOfSatisfying(EventHandlerError.class, error -> {
            assertThat(error.handler().declaringClass()).isEqualTo(UnitListener.class);
            assertThat(error.handler().methodName()).isEqualTo("onPing");
            assertThat(error.event()).isEqualTo(new UnitPing("outside"));
            assertThat(error.cause()).isInstanceOf(NoActiveEventScopeException.class);
        });
    }
}
