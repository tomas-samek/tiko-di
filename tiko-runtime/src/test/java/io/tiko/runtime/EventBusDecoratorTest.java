package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EventBusDecoratorTest {

    @Test
    void decoratorReceivesRawBusAndItsResultIsExposedByTheContainer() {
        AtomicReference<EventBus> seenRaw = new AtomicReference<>();
        EventBus[] decorated = new EventBus[1];

        TikoOptions opts = TikoOptions.builder()
                .eventBusDecorator(raw -> {
                    seenRaw.set(raw);
                    decorated[0] = new ForwardingBus(raw);
                    return decorated[0];
                })
                .build();

        try (Container c = Tiko.create(opts)) {
            assertThat(seenRaw.get()).isInstanceOf(LocalEventBus.class);
            assertThat(c.getEventBus()).isSameAs(decorated[0]);
        }
    }

    private static final class ForwardingBus implements EventBus {
        final EventBus delegate;

        ForwardingBus(EventBus d) {
            this.delegate = d;
        }

        @Override
        public <T> void publish(T event) {
            delegate.publish(event);
        }

        @Override
        public <T> Subscription subscribe(Class<T> t, EventCallback<T> cb) {
            return delegate.subscribe(t, cb);
        }
    }
}
