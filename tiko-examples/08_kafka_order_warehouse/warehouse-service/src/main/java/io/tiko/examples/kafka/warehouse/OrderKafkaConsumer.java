package io.tiko.examples.kafka.warehouse;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventTrigger;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.kafka.annotations.KafkaSource;

@Component(scope = Scope.SINGLETON)
public class OrderKafkaConsumer {

    @KafkaSource(topic = "orders")
    @EventTrigger(eventName = "OrderPlaced")
    public OrderPlaced fromKafka(OrderPlaced payload) {
        // Checked in the bridge, not in a handler: a bridge exception takes the ingest-error path
        // (WarehouseIngestPolicy), while a handler exception would be logged and the record committed.
        if (payload.amount().signum() <= 0) {
            throw new RejectedOrderException(payload.orderId(), "amount must be positive");
        }
        return payload;
    }
}
