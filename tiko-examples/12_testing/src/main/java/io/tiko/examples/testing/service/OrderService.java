package io.tiko.examples.testing.service;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;
import io.tiko.annotations.Inject;
import io.tiko.examples.testing.domain.Clock;
import io.tiko.examples.testing.events.CreateOrderCommand;
import io.tiko.examples.testing.events.OrderCreatedEvent;

/**
 * Builds an {@link OrderCreatedEvent} by charging the configured {@link PaymentGateway}
 * and stamping the current time from the {@link Clock}. Publishing happens via the framework's
 * {@code @EventTrigger} return-as-payload pattern (the {@code EventBus} is not constructor
 * injectable). An imperative {@link #create(String, long)} entry point is exposed so the
 * mocked-payment example can demonstrate runtime override without involving the event bus.
 */
@Component(scope = Scope.SINGLETON)
public class OrderService {

    private final Clock clock;
    private final PaymentGateway gateway;

    @Inject
    public OrderService(Clock clock, PaymentGateway gateway) {
        this.clock = clock;
        this.gateway = gateway;
    }

    @EventHandler
    @EventTrigger(eventName = "OrderCreatedEvent")
    public OrderCreatedEvent onCreateOrder(CreateOrderCommand cmd) {
        var txn = gateway.charge(cmd.customerId(), cmd.amountCents());
        return new OrderCreatedEvent(txn, cmd.customerId(), cmd.amountCents(), clock.now());
    }

    /**
     * Charges the payment gateway and returns the transaction id. Direct method call —
     * useful for tests that exercise dependency overrides without the event bus.
     */
    public String create(String customerId, long amountCents) {
        return gateway.charge(customerId, amountCents);
    }
}
