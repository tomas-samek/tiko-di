package io.tiko.examples.quickstart;

import io.tiko.annotations.Default;

/**
 * Nested database section under {@link AppConfig}.
 */
public record DbConfig(
        String url,
        String user,
        String password,
        @Default("4") int poolSize) {}
