package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;

/**
 * Demonstrates {@code @Produces} returning a third-party {@link AutoCloseable}.
 * The container codegen invokes {@code close()} on the produced beans at scope
 * teardown without the user having to write an explicit {@code @PreDestroy}.
 */
@Component(scope = Scope.SINGLETON)
public class FakePoolFactories {

    @Produces(scope = Scope.SINGLETON, name = "primary")
    public FakePool primaryPool() {
        return new FakePool("primary");
    }

    @Produces(scope = Scope.REQUEST)
    public FakePool requestPool() {
        return new FakePool("request");
    }
}
