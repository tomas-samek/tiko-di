package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PostConstruct;

/**
 * REQUEST-scoped bean whose {@code @PostConstruct} throws. Used to verify routing of
 * lifecycle hook failures through {@code ErrorHandler} before the original throwable
 * propagates. Lazy by scope, so it does not impact other tests' container startup.
 */
@Component(scope = Scope.EVENT)
public class ThrowingPostConstructRequestBean {

    @PostConstruct
    public void boom() {
        throw new IllegalStateException("intentional-postconstruct");
    }
}
