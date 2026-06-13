package io.tiko.runtime;

/**
 * Subclass whose {@code _sig} suffix maps to {@code io.tiko.generated.EventRegistry_sig},
 * a registry present on the classpath but lacking the expected {@code registerHandlers}
 * signature — exercises the version-drift {@code NoSuchMethodException} path (#348).
 */
public final class BootstrapTestContainer_sig extends BootstrapTestContainer {}
