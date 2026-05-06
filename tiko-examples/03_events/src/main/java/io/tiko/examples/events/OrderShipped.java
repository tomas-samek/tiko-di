package io.tiko.examples.events;

public record OrderShipped(String orderId, String customer, double total) {}
