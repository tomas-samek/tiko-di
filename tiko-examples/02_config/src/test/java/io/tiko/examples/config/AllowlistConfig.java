package io.tiko.examples.config;

import io.tiko.annotations.Configuration;
import java.util.Set;

/**
 * Test-only {@code @Configuration} record for {@link ConfigurationSetFieldTest}.
 * Lives at top-level (not nested inside the test class) because the annotation
 * processor's {@code ConfigurationCollector} assumes the enclosing element is a
 * {@code PackageElement}.
 */
@Configuration(prefix = "allow")
public record AllowlistConfig(Set<String> hosts) {}
