package io.tiko.examples.testing.chain;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/**
 * Tail of the hash-colliding singleton chain — see {@link SingletonChainAa} (#338).
 */
@Component(scope = Scope.SINGLETON)
public class SingletonChainBB {}
