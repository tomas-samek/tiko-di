package io.tiko.examples.persistence.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A purchase order with its line items. Persisted across two tables
 * ({@code orders} + {@code order_items}) so that a transaction failing
 * mid-way through inserting items rolls the parent {@code orders} row
 * back too — the cookbook's "why a transaction matters" demonstration.
 */
public record Order(UUID id, String customer, String status, Instant createdAt, List<OrderItem> items) {}
