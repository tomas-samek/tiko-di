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
        return payload;
    }
}
