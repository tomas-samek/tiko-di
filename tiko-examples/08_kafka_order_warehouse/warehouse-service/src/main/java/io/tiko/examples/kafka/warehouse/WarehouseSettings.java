package io.tiko.examples.kafka.warehouse;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

/**
 * The service's own settings, next to tiko-kafka's {@code tiko.kafka} section. The e2e IT runs
 * the shaded fat jar, so it covers an app record and a module record binding together there (#531).
 */
@Configuration(prefix = "warehouse")
public record WarehouseSettings(@Default("main") String site) {}
