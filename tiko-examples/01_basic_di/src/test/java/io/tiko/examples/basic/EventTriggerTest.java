package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.Event;
import io.tiko.runtime.Tiko;
import io.tiko.examples.basic.trigger.AuditedEvent;
import io.tiko.examples.basic.trigger.BatchItemEvent;
import io.tiko.examples.basic.trigger.BatchReceivedEvent;
import io.tiko.examples.basic.trigger.GuardTestEvent;
import io.tiko.examples.basic.trigger.GuardedResult;
import io.tiko.examples.basic.trigger.MultiTriggerEvent;
import io.tiko.examples.basic.trigger.NotifiedEvent;
import io.tiko.examples.basic.trigger.OrderCreatedEvent;
import io.tiko.examples.basic.trigger.OrderProcessedEvent;
import io.tiko.examples.basic.trigger.OrderTriggerService;
import io.tiko.examples.basic.trigger.OrderValidatedEvent;
import io.tiko.examples.basic.trigger.ReplicatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EventTriggerTest {

    @Test
    void handlerReturnValue_becomesPayloadOfTriggeredEvent() {
        try (Container container = Tiko.create()) {
            OrderTriggerService service = container.get(OrderTriggerService.class);
            container.getEventBus().publish(new OrderCreatedEvent(1L, 50.0));

            // OrderCreatedEvent → onOrderCreated returns OrderValidatedEvent → onOrderValidated
            // returns OrderProcessedEvent → onOrderProcessed records it.
            assertThat(service.getReceived())
                .extracting(Object::getClass)
                .contains(OrderCreatedEvent.class, OrderValidatedEvent.class, OrderProcessedEvent.class);
        }
    }

    @Test
    void multipleTriggers_publishHandlerReturnValueOncePerTrigger() {
        try (Container container = Tiko.create()) {
            OrderTriggerService service = container.get(OrderTriggerService.class);
            container.getEventBus().publish(new MultiTriggerEvent(2L));

            // onMultiTrigger has two @EventTrigger annotations → onReplicated fires twice.
            assertThat(service.getReceived())
                .filteredOn(ReplicatedEvent.class::isInstance)
                .hasSize(2);
        }
    }

    @Test
    void guard_allowsPublishWhenItReturnsTrue() {
        try (Container container = Tiko.create()) {
            OrderTriggerService service = container.get(OrderTriggerService.class);
            container.getEventBus().publish(new GuardTestEvent(3L, 250.0));  // > 100, guard passes

            assertThat(service.getReceived())
                .filteredOn(GuardedResult.class::isInstance)
                .hasSize(1);
        }
    }

    @Test
    void guard_suppressesPublishWhenItReturnsFalse() {
        try (Container container = Tiko.create()) {
            OrderTriggerService service = container.get(OrderTriggerService.class);
            container.getEventBus().publish(new GuardTestEvent(4L, 50.0));  // <= 100, guard fails

            assertThat(service.getReceived())
                .filteredOn(GuardedResult.class::isInstance)
                .isEmpty();
            // The handler still ran — only the trigger publish was gated.
            assertThat(service.getReceived())
                .filteredOn(GuardTestEvent.class::isInstance)
                .hasSize(1);
        }
    }

    @Test
    void spread_publishesEachCollectionItemSeparately() {
        try (Container container = Tiko.create()) {
            OrderTriggerService service = container.get(OrderTriggerService.class);
            container.getEventBus().publish(new BatchReceivedEvent(List.of("A", "B", "C")));

            List<BatchItemEvent> items = service.getReceived().stream()
                .filter(BatchItemEvent.class::isInstance)
                .map(BatchItemEvent.class::cast)
                .toList();
            assertThat(items).extracting(BatchItemEvent::sku).containsExactly("A", "B", "C");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void async_triggerPublishesOnDifferentThread() {
        try (Container container = Tiko.create()) {
            OrderTriggerService service = container.get(OrderTriggerService.class);
            Thread publisherThread = Thread.currentThread();
            container.getEventBus().publish(new AuditedEvent(7L));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.getNotifiedOnThread() == null && System.nanoTime() < deadline) {
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            assertThat(service.getNotifiedOnThread())
                .as("async trigger should publish on a different thread")
                .isNotNull()
                .isNotEqualTo(publisherThread);
            assertThat(service.getReceived())
                .filteredOn(NotifiedEvent.class::isInstance)
                .hasSize(1);
        }
    }

    @Test
    void eventWrapper_exposesFullOriginChain() {
        try (Container container = Tiko.create()) {
            OrderTriggerService service = container.get(OrderTriggerService.class);
            container.getEventBus().publish(new OrderCreatedEvent(9L, 250.0));

            Event<?> wrapper = service.getFinalWrapper();
            assertThat(wrapper).isNotNull();
            assertThat(wrapper.getPayload()).isInstanceOf(OrderProcessedEvent.class);

            List<Object> chain = wrapper.getOriginChain();
            assertThat(chain).extracting(Object::getClass).containsExactly(
                OrderCreatedEvent.class,
                OrderValidatedEvent.class,
                OrderProcessedEvent.class);

            assertThat(wrapper.findInChain(OrderCreatedEvent.class)).isPresent();
            assertThat(wrapper.findInChain(OrderCreatedEvent.class).get().id()).isEqualTo(9L);
            assertThat(wrapper.getRoot().getPayload()).isInstanceOf(OrderCreatedEvent.class);
        }
    }
}
