package io.tiko.examples.kafka.order;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

public final class Main {
    public static void main(String[] args) throws Exception {
        TikoOptions opts = TikoOptions.builder()
                .configSource(ConfigSources.classpath("application.yaml"))
                .build();
        try (Container container = Tiko.create(opts);
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            OrderService svc = container.get(OrderService.class);
            System.out.println("order-service ready. Type an amount + ENTER to place an order; Ctrl-D to exit.");
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    BigDecimal amount = new BigDecimal(line.trim());
                    OrderPlaced placed = svc.placeOrder(amount);
                    container.getEventBus().publish(placed);
                    System.out.println("placed: " + placed);
                } catch (NumberFormatException nfe) {
                    System.out.println("(not a number, ignored)");
                }
            }
        }
    }
}
