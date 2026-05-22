package io.tiko.examples.testing.events;

/** Command event published by callers to ask {@code OrderService} to create an order. */
public record CreateOrderCommand(String customerId, long amountCents) {}
