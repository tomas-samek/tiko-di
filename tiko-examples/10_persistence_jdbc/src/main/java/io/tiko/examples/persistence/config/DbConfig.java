package io.tiko.examples.persistence.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

/**
 * Typed binding for the {@code db} section of {@code application.yml}.
 * Loaded via {@code Tiko.create(ConfigSources.classpath("application.yml"))}.
 */
@Configuration(prefix = "db")
public record DbConfig(
        String url,
        String user,
        String password,
        @Default("4") int poolSize) {}
