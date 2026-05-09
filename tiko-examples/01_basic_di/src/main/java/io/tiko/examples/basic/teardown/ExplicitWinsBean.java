package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/**
 * Implements {@link AutoCloseable} <em>and</em> declares an explicit {@code @PreDestroy}.
 * The explicit hook wins; {@code close()} is not auto-invoked, avoiding double-cleanup.
 */
@Component(scope = Scope.SINGLETON)
public class ExplicitWinsBean implements AutoCloseable {

    @Override
    public void close() {
        TeardownRecorder.record("ExplicitWinsBean.close");
    }

    @PreDestroy
    public void shutdown() {
        TeardownRecorder.record("ExplicitWinsBean.preDestroy");
    }
}
