package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;
import java.io.IOException;

/**
 * EVENT-scoped bean built by its own static {@code @Produces} factory, which declares a checked
 * {@link IOException} (#713) and throws {@link #thrownInstance} when a test has set one. Lazy by
 * scope, so the factory only runs when a test resolves the bean inside a unit of work.
 */
@Component(scope = Scope.EVENT)
public class ThrowingCheckedSelfFactoryBean {

    /** Set by a test before resolving this bean; {@code null} lets the factory succeed. */
    public static volatile IOException thrownInstance;

    private ThrowingCheckedSelfFactoryBean() {}

    @Produces
    public static ThrowingCheckedSelfFactoryBean open() throws IOException {
        var failure = thrownInstance;
        if (failure != null) {
            throw failure;
        }
        return new ThrowingCheckedSelfFactoryBean();
    }
}
