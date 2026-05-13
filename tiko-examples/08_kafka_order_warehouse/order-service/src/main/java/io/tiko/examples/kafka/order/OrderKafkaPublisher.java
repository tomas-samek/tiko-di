package io.tiko.examples.kafka.order;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.kafka.annotations.KafkaSink;

@Component(scope = Scope.SINGLETON)
public class OrderKafkaPublisher {

    @KafkaSink(topic = "orders", partitionKey = "orderId")
    public OrderPlaced toKafka(OrderPlaced event) {
        return event;
    }
}
