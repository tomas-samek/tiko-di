package io.tiko.examples.testing.chain;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

/**
 * Head of a two-singleton dependency chain whose storage keys deliberately share a hash
 * code: {@code ...SingletonChainAa} and {@code ...SingletonChainBB} differ only in the
 * {@code Aa}/{@code BB} suffix, which Java's string hash maps to the same value — so both
 * keys land in the same ConcurrentHashMap bin (#338). Exercised by
 * {@code SingletonChainHashCollisionTest}.
 */
@Component(scope = Scope.SINGLETON)
public class SingletonChainAa {

    private final SingletonChainBB dep;

    @Inject
    public SingletonChainAa(SingletonChainBB dep) {
        this.dep = dep;
    }

    public SingletonChainBB dep() {
        return dep;
    }
}
