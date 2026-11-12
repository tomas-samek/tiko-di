package io.tiko.examples.outbox.orders;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

@Configuration(prefix = "outbox")
public record Settings(
        String dbUrl,
        @Default("localhost:9092") String kafkaBootstrap,
        @Default("127.0.0.1") String httpHost,
        int httpPort) {}
