package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PostConstruct;
import java.sql.SQLException;

/**
 * REQUEST-scoped bean whose {@code @PostConstruct} declares and throws a checked
 * {@link SQLException}. Used by {@code CheckedExceptionPropagationTest} to verify
 * the processor's widened catch routes {@code PostConstructFailure} via the
 * configured {@code ErrorHandler} AND sneaky-throws the original throwable with
 * its identity preserved at {@code container.get(...)}. Lazy by scope so the
 * fixture only fires when the test explicitly opens a REQUEST scope and
 * resolves this bean.
 */
@Component(scope = Scope.EVENT)
public class ThrowingCheckedPostConstructBean {

    /** Set by the test before resolving this bean. */
    public static volatile SQLException thrownInstance = new SQLException("default-checked-postconstruct");

    @PostConstruct
    public void start() throws SQLException {
        throw thrownInstance;
    }
}
