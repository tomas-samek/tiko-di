package io.tiko.examples.events;

public record OrderPlaced(String orderId, String customer, double total) {}
