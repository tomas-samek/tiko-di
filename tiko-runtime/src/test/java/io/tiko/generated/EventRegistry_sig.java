package io.tiko.generated;

import io.tiko.EventBus;
import io.tiko.runtime.BootstrapTestContainer_sig;

/**
 * Registry present on the classpath but with the wrong entry-point name — no
 * {@code registerHandlers(EventBus, BootstrapTestContainer_sig)} — simulating a
 * tiko-runtime / tiko-processor version drift (#348). Forces {@code getMethod(...)} to
 * miss so the version-drift {@code ContainerInitializationException} path is exercised.
 */
public final class EventRegistry_sig {

    private EventRegistry_sig() {}

    public static void registerListeners(EventBus eventBus, BootstrapTestContainer_sig container) {
        throw new UnsupportedOperationException("wrong entry point (bus=" + eventBus + ", c=" + container + ")");
    }
}
