package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON)
public class OrderKafkaConsumer {

    public OrderPlaced fromKafka(OrderPlaced payload) {
        return payload;
    }
}
