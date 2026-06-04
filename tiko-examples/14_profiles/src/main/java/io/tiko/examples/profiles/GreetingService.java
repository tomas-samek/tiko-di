package io.tiko.examples.profiles;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

/**
 * Consumer that requires <em>one</em> {@link Greeter} bound at compile time. With profile
 * selection active, only the matching impl is in the generated container; the consumer
 * wires to that single provider. With no profile active, both impls remain visible and
 * the compile fails with an unambiguous "Multiple unnamed providers for type Greeter"
 * error — pick a profile.
 */
@Component(scope = Scope.SINGLETON)
public final class GreetingService {

    private final Greeter greeter;

    @Inject
    public GreetingService(Greeter greeter) {
        this.greeter = greeter;
    }

    public String welcome(String name) {
        return greeter.greet(name);
    }
}
