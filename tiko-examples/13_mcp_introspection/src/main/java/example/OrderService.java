package example;

import example.events.OrderPlaced;
import example.events.OrderValidated;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;
import io.tiko.annotations.Inject;

@Component(scope = Scope.SINGLETON)
public class OrderService {

    private final Orders repo;

    @Inject
    public OrderService(Orders repo) {
        this.repo = repo;
    }

    /** Persists the order — caller publishes the {@code OrderPlaced} event via the bus. */
    public void save(String id, long amountCents) {
        repo.save(id, amountCents);
    }

    @EventHandler
    @EventTrigger(eventName = "OrderValidated")
    public OrderValidated validate(OrderPlaced placed) {
        return new OrderValidated(placed.id(), true);
    }
}
