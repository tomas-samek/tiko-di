package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.sql.SQLException;

/**
 * EVENT-scoped bean whose constructor declares a checked {@link SQLException} (#713) and throws
 * {@link #thrownInstance} when a test has set one. Lazy by scope, so it is only constructed when a
 * test resolves it inside a unit of work.
 */
@Component(scope = Scope.EVENT)
public class ThrowingCheckedConstructorBean {

    /** Set by a test before resolving this bean; {@code null} lets the constructor succeed. */
    public static volatile SQLException thrownInstance;

    @Inject
    public ThrowingCheckedConstructorBean() throws SQLException {
        var failure = thrownInstance;
        if (failure != null) {
            throw failure;
        }
    }
}
