package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/**
 * Test fixture for {@code CloseDrainOrderTest}: a SINGLETON that an async handler still uses while
 * the container closes. It records its teardown in {@link TeardownRecorder}.
 */
@Component(scope = Scope.SINGLETON)
public class DrainedResource {

    private volatile boolean closed;

    public boolean isClosed() {
        return closed;
    }

    @PreDestroy
    public void close() {
        closed = true;
        TeardownRecorder.record("resource torn down");
    }
}
