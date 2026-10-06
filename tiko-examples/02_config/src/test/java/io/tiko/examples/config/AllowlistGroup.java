package io.tiko.examples.config;

import java.util.Set;

/** Test-only nested record inside {@link AllowlistConfig}: a {@code Set<X>} field one level down (#485). */
public record AllowlistGroup(Set<String> members) {}
