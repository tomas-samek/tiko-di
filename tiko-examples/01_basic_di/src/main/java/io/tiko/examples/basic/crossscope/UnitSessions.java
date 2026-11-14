package io.tiko.examples.basic.crossscope;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An EVENT-scoped {@code @Produces} value taken by a SINGLETON {@code @Produces} method. The SINGLETON
 * outlives every unit, so it receives the session's proxy and reads the current unit's session on each
 * call, exactly as a SINGLETON constructor parameter would.
 */
@Component(scope = Scope.SINGLETON)
public class UnitSessions {

    private final AtomicInteger created = new AtomicInteger();

    @Produces(scope = Scope.EVENT)
    public UnitSession session() {
        int number = created.incrementAndGet();
        return () -> number;
    }

    @Produces(scope = Scope.SINGLETON)
    public SessionReporter reporter(UnitSession session) {
        return new SessionReporter(session);
    }
}
