package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;

@Component(scope = Scope.SINGLETON)
public class OrderKafkaPublisher {

    @EventHandler
    public OrderPlaced toKafka(OrderPlaced event) {
        return event;
    }
}
