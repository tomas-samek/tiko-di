package io.tiko.examples.basic.trigger;

public record OrderProcessedEvent(long id, String status, double amount) {}
