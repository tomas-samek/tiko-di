package io.tiko.kafka.runtime.integration.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.annotations.KafkaSource;

@Component(scope = Scope.SINGLETON)
public class ItOrderConsumer {

    @KafkaSource(topic = "it-orders")
    @EventTrigger(eventName = "ItOrderReceived")
    public ItOrderReceived fromKafka(ItOrderPlaced payload) {
        return new ItOrderReceived(payload.orderId(), payload.amount());
    }
}
