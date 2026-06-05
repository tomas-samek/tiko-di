package io.tiko.examples.quickstart;

import io.tiko.annotations.Default;

/**
 * Nested HTTP server section under {@link AppConfig}.
 */
public record ServerConfig(@Default("0") int port) {}
