package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/**
 * REQUEST-scoped {@link AutoCloseable} with no {@code @PreDestroy} — the framework calls
 * {@code close()} as the implicit teardown. Used to verify routing of close-failures
 * through the configured {@code ErrorHandler}.
 */
@Component(scope = Scope.EVENT)
public class ThrowingCloseRequestBean implements AutoCloseable {

    @Override
    public void close() {
        TeardownRecorder.record("ThrowingClose.close");
        throw new IllegalStateException("intentional-close");
    }
}
