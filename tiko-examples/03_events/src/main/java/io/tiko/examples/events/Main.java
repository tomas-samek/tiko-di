package io.tiko.examples.events;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.EventHandlerError;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import java.util.List;

/**
 * Walks through the event-driven features the framework adds: lifecycle events
 * (via {@link Observability}) and {@code @EventTrigger} chains (via
 * {@link OrderWorkflow}), plus two container-level knobs — a custom
 * {@link TikoOptions} and {@link Container#supplyInEventScope(java.util.function.Supplier)}.
 */
public final class Main {

    public static void main(String[] args) {
        // Custom container options. Defaults are sensible, so this is opt-in: here a
        // handler-failure hook that prints instead of logging at WARNING, and a shorter
        // drain window for the async executor at shutdown.
        TikoOptions options = TikoOptions.builder()
                .errorHandler(context -> {
                    if (context instanceof EventHandlerError error) {
                        System.out.printf(
                                java.util.Locale.ROOT,
                                "  [handler failed] %s on attempt %d: %s%n",
                                error.handler(),
                                error.attempts(),
                                error.cause());
                    } else {
                        System.out.println("  [error] " + context);
                    }
                })
                .shutdownTimeout(Duration.ofSeconds(2))
                .build();

        try (Container container = Tiko.create(options)) {
            EventBus bus = container.getEventBus();

            System.out.println();
            System.out.println("== single order, valid ==");
            container.runInEventScope(() -> bus.publish(new OrderPlaced("A1", "alice", 49.99)));

            System.out.println();
            System.out.println("== single order, invalid (guard suppresses shipping) ==");
            container.runInEventScope(() -> bus.publish(new OrderPlaced("A2", "alice", 0.0)));

            System.out.println();
            System.out.println("== batch (spread fans out into individual orders) ==");
            container.runInEventScope(() -> bus.publish(new BatchSubmitted(List.of(
                    new OrderPlaced("B1", "bob", 19.50),
                    new OrderPlaced("B2", "bob", 75.00),
                    new OrderPlaced("B3", "bob", 12.00)))));

            System.out.println();
            System.out.println("== supplyInEventScope: a unit of work that returns a value ==");
            // runInEventScope(Runnable) is the fire-and-forget form. When the unit produces
            // something the caller needs — an id, a receipt, a computed total — use the
            // Supplier form and take the value back out. The unit still opens and tears down
            // exactly the same way; only the return path differs.
            double batchTotal = container.supplyInEventScope(() -> {
                bus.publish(new OrderPlaced("C1", "carol", 30.00));
                return 30.00;
            });
            System.out.printf(java.util.Locale.ROOT, "  caller received: $%.2f%n", batchTotal);
        }
        // ApplicationEndingEvent prints the shutdown report on the way out.
    }
}
