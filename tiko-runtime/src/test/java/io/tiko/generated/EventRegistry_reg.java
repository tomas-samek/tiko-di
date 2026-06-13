package io.tiko.generated;

import io.tiko.EventBus;
import io.tiko.runtime.BootstrapTestContainer_reg;

/**
 * Hand-written stand-in for a generated event registry whose {@code registerHandlers}
 * throws (#348) — e.g. a {@code subscribe} failing on a decorated bus. Lives in
 * {@code io.tiko.generated} with the {@code _reg} suffix so
 * {@code Tiko.registerEventHandlers} resolves it by the same naming convention the real
 * processor output uses.
 */
public final class EventRegistry_reg {

    private EventRegistry_reg() {}

    public static void registerHandlers(EventBus eventBus, BootstrapTestContainer_reg container) {
        throw new IllegalStateException("registration boom");
    }
}
