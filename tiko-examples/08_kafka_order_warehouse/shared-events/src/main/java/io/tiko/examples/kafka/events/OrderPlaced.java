package io.tiko.examples.kafka.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published by order-service when a customer places an order; consumed by
 * warehouse-service to start fulfilment. Same record class on both sides — JSON over
 * Kafka means no schema artifact, just stable field names.
 */
public record OrderPlaced(String orderId, BigDecimal amount, Instant placedAt) {}
