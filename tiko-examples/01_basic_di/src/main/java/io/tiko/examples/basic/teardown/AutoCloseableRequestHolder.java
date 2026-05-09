package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.REQUEST)
public class AutoCloseableRequestHolder implements AutoCloseable {

    @Override
    public void close() {
        TeardownRecorder.record("AutoCloseableRequest");
    }
}
