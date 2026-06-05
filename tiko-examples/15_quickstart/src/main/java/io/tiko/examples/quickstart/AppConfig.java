package io.tiko.examples.quickstart;

import io.tiko.annotations.Configuration;

/**
 * Typed binding for the {@code app} section of {@code application.yml}.
 * Demonstrates the {@code @Configuration} recipe for the orchestrator
 * model: configuration is a value, not a magic late-bound string.
 */
@Configuration(prefix = "app")
public record AppConfig(ServerConfig server, DbConfig db) {}
