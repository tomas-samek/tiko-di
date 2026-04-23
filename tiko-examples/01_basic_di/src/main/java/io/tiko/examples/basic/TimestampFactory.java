package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;

/**
 * Demonstrates a static @Produces factory method (called directly, no instance needed).
 */
@Component(scope = Scope.SINGLETON)
public class TimestampFactory {

    @Produces(scope = Scope.SINGLETON)
    public static Timestamp create() {
        return Timestamp.of(System.nanoTime());
    }
}
