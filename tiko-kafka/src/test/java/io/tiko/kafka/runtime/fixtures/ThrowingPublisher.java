package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON)
public class ThrowingPublisher {

    public OrderPlaced toKafka(OrderPlaced event) {
        throw new RuntimeException("simulated egress failure");
    }
}
