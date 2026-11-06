package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.processing.Resolver;

/**
 * What the rounds of one KSP compilation share: the current round's {@link Resolver} and a round
 * counter. Every model object resolves its symbol through it (see {@link KspRef}), so an object
 * made in an earlier round stays usable in a later one.
 */
final class KspSession {

    private Resolver resolver;
    private int round;

    KspSession(Resolver resolver) {
        this.resolver = resolver;
    }

    Resolver resolver() {
        return resolver;
    }

    int round() {
        return round;
    }

    void enterRound(Resolver next) {
        resolver = next;
        round++;
    }
}
