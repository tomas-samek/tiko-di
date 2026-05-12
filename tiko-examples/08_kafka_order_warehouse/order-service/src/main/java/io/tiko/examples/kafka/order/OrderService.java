package io.tiko.examples.kafka.order;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.examples.kafka.events.OrderPlaced;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component(scope = Scope.SINGLETON)
public class OrderService {

    public OrderPlaced placeOrder(BigDecimal amount) {
        return new OrderPlaced(UUID.randomUUID().toString(), amount, Instant.now());
    }
}
