package io.tiko.examples.events;

public record OrderValidated(String orderId, String customer, double total, boolean valid) {}
