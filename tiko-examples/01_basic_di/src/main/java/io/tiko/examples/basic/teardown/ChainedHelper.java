package io.tiko.examples.basic.teardown;

import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

/** A PROTOTYPE between two SINGLETONs: each consumer gets its own helper around the shared pool. */
@Component
public class ChainedHelper {

    private final ChainedPool pool;

    @Inject
    public ChainedHelper(ChainedPool pool) {
        this.pool = pool;
    }

    public ChainedPool pool() {
        return pool;
    }
}
