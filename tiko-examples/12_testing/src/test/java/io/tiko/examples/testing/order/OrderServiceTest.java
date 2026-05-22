package io.tiko.examples.testing.order;

import io.tiko.EventBus;
import io.tiko.examples.testing.events.CreateOrderCommand;
import io.tiko.examples.testing.events.OrderCreatedEvent;
import io.tiko.test.RecordingEventBus;
import io.tiko.test.TikoTest;
import org.junit.jupiter.api.Test;

@TikoTest
class OrderServiceTest {

    @Test
    void createsOrderAndPublishesEvent(EventBus bus, RecordingEventBus rec) {
        // Publishing the CreateOrderCommand drives OrderService.onCreateOrder, whose
        // @EventTrigger re-publishes the resulting OrderCreatedEvent on the same bus.
        // RecordingEventBus captures both — the test asserts on the downstream event.
        bus.publish(new CreateOrderCommand("alice", 4200L));

        rec.assertPublished(OrderCreatedEvent.class)
                .withPayload((OrderCreatedEvent e) -> e.customerId().equals("alice") && e.amountCents() == 4200L);
    }
}
