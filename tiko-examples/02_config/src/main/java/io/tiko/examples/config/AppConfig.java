package io.tiko.examples.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

/**
 * Application-level configuration. The {@code server} field demonstrates
 * nested-record support (#17): {@link ServerConfig} is a plain record (not itself
 * annotated {@code @Configuration}) bound automatically as a nested section under
 * the {@code app} prefix.
 */
@Configuration(prefix = "app")
public record AppConfig(String name, @Default("INFO") String logLevel, ServerConfig server) {}
