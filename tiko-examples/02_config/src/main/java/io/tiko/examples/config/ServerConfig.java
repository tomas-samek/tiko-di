package io.tiko.examples.config;

import io.tiko.annotations.Default;

/**
 * Plain record used as a nested section inside {@link AppConfig} to demonstrate
 * nested-record support (#17). Note this is NOT itself annotated {@code @Configuration} —
 * it's bound by recursion through the parent's generated nested coercer.
 */
public record ServerConfig(
    String host,
    @Default("8080") int port
) {}
