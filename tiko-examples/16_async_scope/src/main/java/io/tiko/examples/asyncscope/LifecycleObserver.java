package io.tiko.examples.asyncscope;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.EventStartedEvent;
import java.util.concurrent.atomic.AtomicInteger;

@Component(scope = Scope.SINGLETON)
public class LifecycleObserver {
    public static final AtomicInteger SEEN = new AtomicInteger();
    public static final AtomicInteger STARTED_TOTAL = new AtomicInteger();

    @EventHandler(async = true)
    public void onUnitStarted(EventStartedEvent event) {
        SEEN.incrementAndGet();
        STARTED_TOTAL.incrementAndGet();
    }
}
