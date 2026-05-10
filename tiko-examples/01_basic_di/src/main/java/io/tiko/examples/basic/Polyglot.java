package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Pick;

/**
 * Demonstrates {@code @Pick} — the class-literal alternative to {@code @Named}
 * for choosing between multiple {@link Greeter} implementations.
 *
 * <p>Where {@code @Named("english")} relies on a string the IDE can't follow,
 * {@code @Pick(EnglishGreeter.class)} uses a class literal: typos become compile
 * errors, rename refactors update every usage, and the implementation choice is
 * visible to anyone reading the constructor.
 */
@Component(scope = Scope.SINGLETON)
public class Polyglot {

    private final Greeter english;
    private final Greeter spanish;

    @Inject
    public Polyglot(@Pick(EnglishGreeter.class) Greeter english, @Pick(SpanishGreeter.class) Greeter spanish) {
        this.english = english;
        this.spanish = spanish;
    }

    public String greetIn(String language) {
        return switch (language) {
            case "english" -> english.greet();
            case "spanish" -> spanish.greet();
            default -> throw new IllegalArgumentException("unknown language: " + language);
        };
    }
}
