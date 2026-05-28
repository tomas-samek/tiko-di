package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Named;

/**
 * Injection-site {@code @Named} into a named {@code @Component} (#242). Greeter has two named impls
 * (english/spanish) and no unnamed default, so this constructor must resolve via the qualifier. The
 * pattern previously generated a non-compiling factory; it now routes through the typed
 * {@code get(Greeter.class, "english")} dispatcher.
 */
@Component(scope = Scope.SINGLETON)
public class NamedGreeterConsumer {

    private final Greeter greeter;

    @Inject
    public NamedGreeterConsumer(@Named("english") Greeter greeter) {
        this.greeter = greeter;
    }

    public String greeting() {
        return greeter.greet();
    }
}
