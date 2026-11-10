package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;

/** Records that its container shut it down, for {@code TikoTestTeardownTest} (#637). */
@Component(scope = Scope.SINGLETON)
public class ShutdownProbe {

    private static final AtomicBoolean DESTROYED = new AtomicBoolean();

    @PreDestroy
    public void destroyed() {
        DESTROYED.set(true);
    }

    public static void reset() {
        DESTROYED.set(false);
    }

    public static boolean wasDestroyed() {
        return DESTROYED.get();
    }
}
