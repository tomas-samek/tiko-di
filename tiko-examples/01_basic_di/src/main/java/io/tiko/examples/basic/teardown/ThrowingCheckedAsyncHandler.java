package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.io.IOException;

/**
 * An async handler that declares and throws a checked exception. The generated dispatch runs
 * it as a {@code Runnable}, which declares none; {@code CheckedExceptionPropagationTest} checks
 * that the {@code ErrorHandler} still gets the handler's own exception, not a wrapper.
 */
@Component(scope = Scope.SINGLETON)
public class ThrowingCheckedAsyncHandler {

    @EventHandler(async = true)
    public void on(CheckedAsyncPing ping) throws IOException {
        throw ping.toThrow();
    }
}
