package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.processing.Resolver;
import java.util.function.Function;

/**
 * A KSP symbol that stays valid across rounds. KSP2 symbols die with their round: once a round
 * has generated files, touching a symbol from before it throws {@code
 * KaInvalidLifetimeOwnerAccessException} ("PSI has changed since creation"). A ref therefore
 * remembers how to find its symbol again and, the first time it is read in a later round, looks
 * it up through that round's {@link Resolver}.
 */
final class KspRef<T> {

    private final KspSession session;
    private final Function<Resolver, T> locate;
    private T node;
    private int round;

    private KspRef(KspSession session, T node, Function<Resolver, T> locate) {
        this.session = session;
        this.locate = locate;
        this.node = node;
        this.round = session.round();
    }

    /** A ref holding {@code node} for the current round, found again by {@code locate} in later ones. */
    static <T> KspRef<T> of(KspSession session, T node, Function<Resolver, T> locate) {
        return new KspRef<>(session, node, locate);
    }

    /** The symbol, valid in the current round. */
    T get() {
        if (round != session.round()) {
            var found = locate.apply(session.resolver());
            if (found == null) {
                // Not the stale node in the message: reading it would throw.
                throw new IllegalStateException(
                        "A KSP symbol from an earlier round is gone in round " + session.round());
            }
            node = found;
            round = session.round();
        }
        return node;
    }

    /** A ref to what {@code step} reaches from this symbol, found again through this ref. */
    <R> KspRef<R> derive(Function<T, R> step) {
        return new KspRef<>(session, step.apply(get()), resolver -> step.apply(get()));
    }
}
