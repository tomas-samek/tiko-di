package io.tiko.examples.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;
import java.time.Duration;
import java.util.Optional;

@Configuration(prefix = "db")
public record DbConfig(String url, @Default("10") int maxConnections, Optional<Duration> connectTimeout) {}
