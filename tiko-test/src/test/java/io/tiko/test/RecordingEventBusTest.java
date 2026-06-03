package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.Subscription;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordingEventBusTest {

    record FooEvent(String id) {}

    record BarEvent(int n) {}

    @Test
    void capturesPublishesAndForwardsToDelegate() {
        List<Object> delegateReceived = new ArrayList<>();
        EventBus delegate = new EventBus() {
            @Override
            public <T> void publish(T event) {
                delegateReceived.add(event);
            }

            @Override
            public <T> Subscription subscribe(Class<T> t, EventCallback<T> c) {
                return new NoopSubscription();
            }
        };
        var rec = new RecordingEventBus(delegate);

        rec.publish(new FooEvent("a"));
        rec.publish(new BarEvent(7));

        assertThat(delegateReceived).hasSize(2);
        assertThat(rec.events()).hasSize(2);
        assertThat(rec.events(FooEvent.class)).containsExactly(new FooEvent("a"));
    }

    @Test
    void assertPublishedPassesWhenTypeMatched() {
        var rec = newRec();
        rec.publish(new FooEvent("a"));
        rec.assertPublished(FooEvent.class);
    }

    @Test
    void assertPublishedFailsWithDiagnosticWhenAbsent() {
        var rec = newRec();
        rec.publish(new BarEvent(1));
        assertThatThrownBy(() -> rec.assertPublished(FooEvent.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FooEvent")
                .hasMessageContaining("BarEvent");
    }

    @Test
    void withPayloadMatchesPredicate() {
        var rec = newRec();
        rec.publish(new FooEvent("xyz"));
        rec.assertPublished(FooEvent.class).withPayload((FooEvent e) -> e.id().equals("xyz"));
    }

    @Test
    void assertPublishedExactlyCountsAcrossPublishes() {
        var rec = newRec();
        rec.publish(new FooEvent("a"));
        rec.publish(new FooEvent("b"));
        rec.assertPublishedExactly(2, FooEvent.class);
    }

    @Test
    void assertNoneOfFailsIfPresent() {
        var rec = newRec();
        rec.publish(new FooEvent("a"));
        assertThatThrownBy(() -> rec.assertNoneOf(FooEvent.class)).isInstanceOf(AssertionError.class);
    }

    @Test
    void clearResetsCaptureBuffer() {
        var rec = newRec();
        rec.publish(new FooEvent("a"));
        rec.clear();
        assertThat(rec.events()).isEmpty();
    }

    private static RecordingEventBus newRec() {
        return new RecordingEventBus(new EventBus() {
            @Override
            public <T> void publish(T event) {
                // no-op: test stub
            }

            @Override
            public <T> Subscription subscribe(Class<T> t, EventCallback<T> c) {
                return new NoopSubscription();
            }
        });
    }

    private static final class NoopSubscription implements Subscription {
        @Override
        public void unsubscribe() {
            // no-op: test stub
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }
}
