package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EVENT-scoped probe for #347: resolving it requires an open unit-of-work frame, and its
 * {@code @PreDestroy} records teardown — so a test can prove a consumed Kafka message opens
 * (and closes) an EVENT scope. Counters are static so the test observes create/destroy
 * across the consumer thread; {@link #reset()} clears them per test.
 */
@Component(scope = Scope.EVENT)
public class UnitScopeProbe {

    public static final AtomicInteger CREATED = new AtomicInteger();
    public static final AtomicInteger DESTROYED = new AtomicInteger();

    public UnitScopeProbe() {
        CREATED.incrementAndGet();
    }

    @PreDestroy
    public void onDestroy() {
        DESTROYED.incrementAndGet();
    }

    public static void reset() {
        CREATED.set(0);
        DESTROYED.set(0);
    }
}
