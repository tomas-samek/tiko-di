package io.tiko.runtime;

/**
 * Subclass whose simple name ends in {@code _reg} so {@code Tiko.registerEventHandlers}
 * derives the registry FQN {@code io.tiko.generated.EventRegistry_reg} (#348). Paired with
 * the hand-written {@code EventRegistry_reg} test fixture whose {@code registerHandlers}
 * throws, to prove a real registration failure is no longer swallowed.
 */
public final class BootstrapTestContainer_reg extends BootstrapTestContainer {}
