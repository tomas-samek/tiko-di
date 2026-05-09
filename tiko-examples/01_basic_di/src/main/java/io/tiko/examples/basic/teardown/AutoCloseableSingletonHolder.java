package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/**
 * Demonstrates zero-annotation cleanup: implements {@link AutoCloseable} but declares
 * no {@code @PreDestroy}. The container codegen treats {@code close()} as an implicit
 * destroy hook and invokes it during {@code shutdown()}.
 */
@Component(scope = Scope.SINGLETON)
public class AutoCloseableSingletonHolder implements AutoCloseable {

    @Override
    public void close() {
        TeardownRecorder.record("AutoCloseableSingleton");
    }
}
