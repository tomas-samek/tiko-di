package io.tiko.examples.multimodule.config.core;

import io.tiko.annotations.Configuration;

import java.time.Duration;

/**
 * Core module configuration. Defaults are baked into the module's jar at
 * {@code META-INF/tiko/defaults.yaml}; users can override any value via the
 * application-level config file.
 */
@Configuration(prefix = "core")
public record CoreConfig(int retries, Duration timeout) {}
