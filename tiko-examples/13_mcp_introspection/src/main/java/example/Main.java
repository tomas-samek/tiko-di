package example;

import example.events.OrderPlaced;
import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try (Container c = Tiko.create(ConfigSources.classpath("config.yaml"))) {
            EventBus bus = c.getEventBus();
            var orderService = c.get(OrderService.class);
            c.runInRequestScope(() -> c.runInEventScope(() -> {
                orderService.save("ord-1", 4200L);
                bus.publish(new OrderPlaced("ord-1", 4200L));
            }));
        }
    }
}
