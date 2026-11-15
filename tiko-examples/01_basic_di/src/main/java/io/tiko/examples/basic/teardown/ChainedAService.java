package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

/**
 * A SINGLETON that reaches {@link ChainedPool} only through the PROTOTYPE {@link ChainedHelper}. It
 * still uses the pool while it closes, so it must be torn down before the pool is.
 */
@Component(scope = Scope.SINGLETON)
public class ChainedAService implements AutoCloseable {

    private final ChainedHelper helper;

    @Inject
    public ChainedAService(ChainedHelper helper) {
        this.helper = helper;
    }

    @Override
    public void close() {
        TeardownRecorder.record(
                "ChainedAService.close(poolClosed=" + helper.pool().isClosed() + ")");
    }
}
