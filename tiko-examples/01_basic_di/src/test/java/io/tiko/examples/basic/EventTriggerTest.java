package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.Event;
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
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.AsyncSourceEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ChainA;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ChainB;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ChainC;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.DownstreamEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.EdgeItem;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.EmptySpreadEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.MapSpreadEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.NullSpreadEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.RecordingGuard;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ShortCircuitEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.ShortCircuitResult;
import io.tiko.examples.basic.trigger.TriggerEdgeFixtures.SourceEvent;
import io.tiko.examples.basic.trigger.TriggerEdgeService;
import io.tiko.runtime.Tiko;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
            container.getEventBus().publish(new GuardTestEvent(3L, 250.0)); // > 100, guard passes

            assertThat(service.getReceived())
                    .filteredOn(GuardedResult.class::isInstance)
                    .hasSize(1);
        }
    }

    @Test
    void guard_suppressesPublishWhenItReturnsFalse() {
        try (Container container = Tiko.create()) {
            OrderTriggerService service = container.get(OrderTriggerService.class);
            container.getEventBus().publish(new GuardTestEvent(4L, 50.0)); // <= 100, guard fails

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
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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
            assertThat(chain)
                    .extracting(Object::getClass)
                    .containsExactly(OrderCreatedEvent.class, OrderValidatedEvent.class, OrderProcessedEvent.class);

            assertThat(wrapper.findInChain(OrderCreatedEvent.class)).isPresent();
            assertThat(wrapper.findInChain(OrderCreatedEvent.class).get().id()).isEqualTo(9L);
            assertThat(wrapper.getRoot().getPayload()).isInstanceOf(OrderCreatedEvent.class);
        }
    }

    // ─── #162: exception suppresses the downstream trigger ──────────────────────

    @Test
    void handlerExceptionSuppressesTriggeredEvent() {
        try (Container container = Tiko.create()) {
            TriggerEdgeService svc = container.get(TriggerEdgeService.class);
            container.getEventBus().publish(new SourceEvent(1L));

            // The source handler ran (and threw, routed to the error handler); the downstream
            // trigger must NOT fire.
            assertThat(svc.getReceived())
                    .filteredOn(SourceEvent.class::isInstance)
                    .hasSize(1);
            assertThat(svc.getReceived())
                    .filteredOn(DownstreamEvent.class::isInstance)
                    .isEmpty();
        }
    }

    @Test
    void asyncHandlerExceptionSuppressesTriggeredEvent() {
        try (Container container = Tiko.create()) {
            TriggerEdgeService svc = container.get(TriggerEdgeService.class);
            container.getEventBus().publish(new AsyncSourceEvent(1L));

            // The handler throws synchronously before the async trigger is scheduled, so the
            // async trigger is suppressed too.
            assertThat(svc.getReceived())
                    .filteredOn(AsyncSourceEvent.class::isInstance)
                    .hasSize(1);
            assertThat(svc.getReceived())
                    .filteredOn(DownstreamEvent.class::isInstance)
                    .isEmpty();
        }
    }

    @Test
    void midChainThrowSuppressesDownstreamTrigger() {
        try (Container container = Tiko.create()) {
            TriggerEdgeService svc = container.get(TriggerEdgeService.class);
            container.getEventBus().publish(new ChainA(1L));

            // A -> B fires; B's handler throws -> the C trigger is suppressed.
            assertThat(svc.getReceived()).filteredOn(ChainA.class::isInstance).hasSize(1);
            assertThat(svc.getReceived()).filteredOn(ChainB.class::isInstance).hasSize(1);
            assertThat(svc.getReceived()).filteredOn(ChainC.class::isInstance).isEmpty();
        }
    }

    // ─── #163.A: spread edge cases ──────────────────────────────────────────────

    @Test
    void spreadEmptyCollectionPublishesNothing() {
        try (Container container = Tiko.create()) {
            TriggerEdgeService svc = container.get(TriggerEdgeService.class);
            container.getEventBus().publish(new EmptySpreadEvent(1L));

            assertThat(svc.getReceived()).filteredOn(EdgeItem.class::isInstance).isEmpty();
        }
    }

    @Test
    void spreadSkipsNullElements() {
        try (Container container = Tiko.create()) {
            TriggerEdgeService svc = container.get(TriggerEdgeService.class);
            container.getEventBus().publish(new NullSpreadEvent(1L));

            List<EdgeItem> items = svc.getReceived().stream()
                    .filter(EdgeItem.class::isInstance)
                    .map(EdgeItem.class::cast)
                    .toList();
            assertThat(items).extracting(EdgeItem::sku).containsExactly("X", "Y");
        }
    }

    @Test
    void spreadOnMapPublishesMapAsSingleEventNotEntries() {
        try (Container container = Tiko.create()) {
            TriggerEdgeService svc = container.get(TriggerEdgeService.class);
            container.getEventBus().publish(new MapSpreadEvent(1L));

            // A Map is not a Collection/array/Iterable, so spread degrades to a single publish of
            // the Map itself — its EdgeItem values are NOT spread, so no EdgeItem is published.
            assertThat(svc.getReceived()).filteredOn(EdgeItem.class::isInstance).isEmpty();
        }
    }

    // ─── #163.B: guard chain short-circuits in source order ─────────────────────

    @Test
    void guardChainShortCircuitsInSourceOrder() {
        RecordingGuard.reset();
        try (Container container = Tiko.create()) {
            TriggerEdgeService svc = container.get(TriggerEdgeService.class);
            container.getEventBus().publish(new ShortCircuitEvent(1L));

            assertThat(RecordingGuard.CONSULTED.get())
                    .as("the second guard must be short-circuited when the first denies")
                    .isFalse();
            assertThat(svc.getReceived())
                    .filteredOn(ShortCircuitResult.class::isInstance)
                    .isEmpty();
        }
    }

    // ─── #163.C: findInChain for an unrelated type ──────────────────────────────

    @Test
    void findInChainReturnsEmptyForUnrelatedType() {
        try (Container container = Tiko.create()) {
            OrderTriggerService order = container.get(OrderTriggerService.class);
            container.getEventBus().publish(new OrderCreatedEvent(9L, 250.0));

            Event<?> wrapper = order.getFinalWrapper();
            assertThat(wrapper).isNotNull();
            assertThat(wrapper.findInChain(java.time.Instant.class))
                    .as("findInChain returns empty for a type not in the chain")
                    .isEmpty();
        }
    }

    // ─── #163.D: a failed chain does not pollute the next one ───────────────────

    @Test
    void failedChainDoesNotPolluteNextChain() {
        try (Container container = Tiko.create()) {
            OrderTriggerService order = container.get(OrderTriggerService.class);

            // First inbound event throws mid-chain and its trigger is suppressed...
            container.getEventBus().publish(new SourceEvent(1L));
            // ...the next inbound event of a different type starts a fresh origin chain.
            container.getEventBus().publish(new OrderCreatedEvent(2L, 100.0));

            List<Object> chain = order.getFinalWrapper().getOriginChain();
            assertThat(chain)
                    .extracting(Object::getClass)
                    .containsExactly(OrderCreatedEvent.class, OrderValidatedEvent.class, OrderProcessedEvent.class);
        }
    }
}
