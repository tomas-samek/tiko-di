package io.tiko.examples.testing.async;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.examples.testing.events.OrderCreatedEvent;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async {@code @EventHandler} fixture for the {@code awaitAsyncDispatch} demo. Declared
 * under {@code src/test/java} because it is purely a test concern; the annotation processor
 * runs on the test classpath and wires it into the test container.
 */
@Component(scope = Scope.SINGLETON)
public class AsyncListener {
    public static final AtomicInteger received = new AtomicInteger();

    @EventHandler(async = true)
    public void onOrder(OrderCreatedEvent event) {
        received.incrementAndGet();
    }
}
