package io.tiko.examples.outbox.warehouse;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventTrigger;
import io.tiko.examples.outbox.Messages;
import io.tiko.examples.outbox.Messages.OrderPlaced;
import io.tiko.examples.outbox.Messages.ReservationDecided;
import io.tiko.kafka.annotations.KafkaSource;
import java.sql.SQLException;

@Component(scope = Scope.SINGLETON)
public class OrderConsumer {
    private final WarehouseStore warehouse;

    public OrderConsumer(WarehouseStore warehouse) {
        this.warehouse = warehouse;
    }

    @KafkaSource(topic = Messages.ORDERS)
    @EventTrigger(eventName = "ReservationDecided")
    public ReservationDecided receive(OrderPlaced order) {
        // The broker offset advances only after this transaction commits and the bridge returns.
        try {
            return warehouse.reserve(order);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot reserve stock for " + order.orderId(), failure);
        }
    }
}
