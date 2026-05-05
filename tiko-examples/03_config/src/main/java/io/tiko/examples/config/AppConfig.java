package io.tiko.examples.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

@Configuration(prefix = "app")
public record AppConfig(
    String name,
    @Default("INFO") String logLevel
) {}
