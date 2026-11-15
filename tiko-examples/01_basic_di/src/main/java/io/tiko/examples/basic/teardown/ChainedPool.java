package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/** The SINGLETON at the end of the chain; {@link ChainedAService} reads it while closing. */
@Component(scope = Scope.SINGLETON)
public class ChainedPool implements AutoCloseable {

    private volatile boolean closed;

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        TeardownRecorder.record("ChainedPool.close");
    }
}
