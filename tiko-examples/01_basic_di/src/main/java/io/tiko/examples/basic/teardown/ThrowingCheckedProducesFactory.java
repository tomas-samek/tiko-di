package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;
import java.sql.SQLException;

/**
 * Factory whose {@code @Produces} method declares and throws a checked
 * {@link SQLException}. The output is REQUEST-scoped so resolution only fires
 * when the test explicitly opens a REQUEST scope and asks for the produced
 * type — keeps the fixture out of every other test's container start path.
 *
 * <p>Used by {@code CheckedExceptionPropagationTest} to verify the processor's
 * per-factory {@code invokeFactory_<id>()} helper routes {@code ProduceFailure}
 * via the configured {@code ErrorHandler} AND sneaky-throws the original
 * throwable with its identity preserved at {@code container.get(...)}.
 */
@Component(scope = Scope.SINGLETON)
public class ThrowingCheckedProducesFactory {

    /** Set by the test before resolving the produced type. */
    public static volatile SQLException thrownInstance = new SQLException("default-checked-produces");

    @Produces(scope = Scope.REQUEST)
    public CheckedProducesOutput failingResource() throws SQLException {
        throw thrownInstance;
    }
}
