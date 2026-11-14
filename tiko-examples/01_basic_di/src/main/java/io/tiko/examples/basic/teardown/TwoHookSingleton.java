package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/**
 * SINGLETON with two {@code @PreDestroy} methods (#712). Each records itself; the first fails when
 * {@link #failFirstHook} is set, so a test can check that the second still runs.
 */
@Component(scope = Scope.SINGLETON)
public class TwoHookSingleton {

    /** Set by a test, and reset by it, to make {@link #flush()} throw. */
    public static volatile boolean failFirstHook;

    @PreDestroy
    public void flush() {
        TeardownRecorder.record("TwoHook.flush");
        if (failFirstHook) {
            throw new IllegalStateException("flush failed");
        }
    }

    @PreDestroy
    public void release() {
        TeardownRecorder.record("TwoHook.release");
    }
}
