package io.tiko.examples.testing.lifecycle;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.concurrent.atomic.AtomicInteger;

/** A SINGLETON nothing depends on and that handles no event: counts how often it is constructed. */
@Component(scope = Scope.SINGLETON)
public class OnDemandSingleton {

    private static final AtomicInteger CONSTRUCTED = new AtomicInteger();

    public OnDemandSingleton() {
        CONSTRUCTED.incrementAndGet();
    }

    public static int constructed() {
        return CONSTRUCTED.get();
    }
}
