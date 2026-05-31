package io.tiko.examples.events;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.runtime.Tiko;
import java.util.List;

/**
 * Walks through the two event-driven features the framework adds:
 * lifecycle events (via {@link Observability}) and {@code @EventTrigger}
 * chains (via {@link OrderWorkflow}).
 */
public final class Main {

    public static void main(String[] args) {
        try (Container container = Tiko.create()) {
            EventBus bus = container.getEventBus();

            System.out.println();
            System.out.println("== single order, valid ==");
            container.runInEventScope(
                    () -> container.runInEventScope(() -> bus.publish(new OrderPlaced("A1", "alice", 49.99))));

            System.out.println();
            System.out.println("== single order, invalid (guard suppresses shipping) ==");
            container.runInEventScope(
                    () -> container.runInEventScope(() -> bus.publish(new OrderPlaced("A2", "alice", 0.0))));

            System.out.println();
            System.out.println("== batch (spread fans out into individual orders) ==");
            container.runInEventScope(() -> container.runInEventScope(() -> bus.publish(new BatchSubmitted(List.of(
                    new OrderPlaced("B1", "bob", 19.50),
                    new OrderPlaced("B2", "bob", 75.00),
                    new OrderPlaced("B3", "bob", 12.00))))));
        }
        // ApplicationEndingEvent prints the shutdown report on the way out.
    }
}
