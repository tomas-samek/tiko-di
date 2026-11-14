package io.tiko.examples.testing.lifecycle;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.ApplicationStartedEvent;
import java.util.concurrent.atomic.AtomicInteger;

/** A SINGLETON that handles {@link ApplicationStartedEvent}: counts how often it is constructed. */
@Component(scope = Scope.SINGLETON)
public class StartedSingleton {

    private static final AtomicInteger CONSTRUCTED = new AtomicInteger();

    public StartedSingleton() {
        CONSTRUCTED.incrementAndGet();
    }

    @EventHandler
    public void onStarted(ApplicationStartedEvent started) {
        // Delivering the event is what constructs this component.
    }

    public static int constructed() {
        return CONSTRUCTED.get();
    }
}
