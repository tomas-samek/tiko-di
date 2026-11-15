package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;

/**
 * A {@code @Produces} method that takes the output of {@link ThrowingCheckedProducesFactory},
 * which always fails. This method is never entered: the failure happens while its parameter is
 * resolved. {@code CheckedExceptionPropagationTest} checks that the {@code ErrorHandler} hears of
 * the failure once, from the method that threw, and not a second time from this one.
 */
@Component(scope = Scope.SINGLETON)
public class ChainedProducesFactory {

    @Produces(scope = Scope.EVENT)
    public ChainedProducesOutput chained(CheckedProducesOutput failing) {
        return new ChainedProducesOutput();
    }
}
