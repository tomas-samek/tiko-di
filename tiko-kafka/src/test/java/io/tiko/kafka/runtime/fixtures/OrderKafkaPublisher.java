package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON)
public class OrderKafkaPublisher {

    public OrderPlaced toKafka(OrderPlaced event) {
        return event;
    }
}
