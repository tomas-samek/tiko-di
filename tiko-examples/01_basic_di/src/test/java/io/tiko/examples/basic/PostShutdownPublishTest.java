package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.events.EventStartedEvent;
import io.tiko.runtime.Tiko;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Publishing on a retained bus after {@code shutdown()} must not execute generated
 * {@code EventHandler} dispatch (#337): the handler singletons have been destroyed in
 * Phase 4, so running them would mean use-after-close — and in lazy (aggregator) mode
 * dispatch could even construct a brand-new singleton after shutdown. The generated
 * dispatchers gate on the container's stopped flag and drop the delivery.
 */
class PostShutdownPublishTest {

    @Test
    void publishAfterShutdownDoesNotDispatchToHandlers() {
        Container container = Tiko.create();
        LifecycleRecorder recorder = container.get(LifecycleRecorder.class);
        EventBus bus = container.getEventBus();
        container.shutdown();
        int recordedAtShutdown = recorder.getEvents().size();

        bus.publish(new EventStartedEvent("post-shutdown", Instant.now()));

        assertThat(recorder.getEvents()).hasSize(recordedAtShutdown);
    }
}
