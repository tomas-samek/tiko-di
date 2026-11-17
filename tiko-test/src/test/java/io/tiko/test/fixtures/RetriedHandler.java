package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An async handler that succeeds on its third attempt, with a backoff long enough for a test to
 * look at it between attempts (#761). Inert in every test that does not publish {@link RetriedPing}.
 */
@Component(scope = Scope.SINGLETON)
public class RetriedHandler {

    private static final AtomicInteger ATTEMPTS = new AtomicInteger();

    @EventHandler(async = true, retries = 2, backoff = "PT0.3S")
    public void onPing(RetriedPing ping) {
        if (ATTEMPTS.incrementAndGet() < 3) {
            throw new IllegalStateException("attempt " + ATTEMPTS.get() + " fails");
        }
    }

    public static void reset() {
        ATTEMPTS.set(0);
    }

    public static int attempts() {
        return ATTEMPTS.get();
    }
}
