package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.concurrent.atomic.AtomicInteger;

@Component(scope = Scope.SINGLETON)
public class ThrowingBridge {

    public final AtomicInteger callCount = new AtomicInteger(0);

    public OrderPlaced fromKafka(OrderPlaced payload) {
        callCount.incrementAndGet();
        if (callCount.get() <= 2) {
            throw new RuntimeException("simulated bridge failure on call " + callCount.get());
        }
        return payload;
    }
}
