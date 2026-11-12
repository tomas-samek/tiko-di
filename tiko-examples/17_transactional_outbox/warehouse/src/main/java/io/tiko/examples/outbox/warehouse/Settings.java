package io.tiko.examples.outbox.warehouse;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

@Configuration(prefix = "outbox")
public record Settings(
        String dbUrl, @Default("localhost:9092") String kafkaBootstrap) {}
