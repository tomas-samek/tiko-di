package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Named;

/**
 * The canonical {@code @Named} injection example (#170). {@link Greeter} has two named impls
 * (english/spanish) and no unnamed default, so this constructor disambiguates with a
 * <em>string</em> qualifier.
 *
 * <p><strong>Why {@code @Named} and not {@code @Pick}?</strong> Reach for {@code @Named} when the
 * qualifier is genuinely a string — externally configured, supplied at runtime, or familiar to
 * teams coming from {@code jakarta.inject}. When the disambiguator is a class literal you control at
 * compile time, prefer {@code @Pick(EnglishGreeter.class)} instead (see {@code Polyglot} /
 * {@code PickerConsumer}) for refactor safety. Both are first-class; they suit different situations.
 *
 * <p>(Injection-site {@code @Named} resolving to a named {@code @Component} generated a
 * non-compiling factory until #242/#243; it now routes through the typed
 * {@code get(Greeter.class, "english")} dispatcher.)
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
