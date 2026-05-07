package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;

/**
 * Test fixture: a SINGLETON component with a handler that always throws,
 * used by EventErrorIsolationIntegrationTest.
 */
@Component(scope = Scope.SINGLETON)
public class ThrowingHandler {

    @EventHandler
    public void onPing(Ping event) {
        throw new IllegalStateException("integration boom");
    }
}
