package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;

/**
 * Test fixture for async error-routing integration tests.
 */
@Component(scope = Scope.SINGLETON)
public class AsyncThrower {

    @EventHandler(async = true)
    public void onPing(AsyncPing event) {
        throw new IllegalStateException("async boom");
    }
}
