package io.tiko.kafka.runtime.integration.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.kafka.annotations.KafkaSink;

@Component(scope = Scope.SINGLETON)
public class ItOrderPublisher {

    @KafkaSink(topic = "it-orders", partitionKey = "orderId")
    public ItOrderPlaced toKafka(ItOrderPlaced event) {
        return event;
    }
}
