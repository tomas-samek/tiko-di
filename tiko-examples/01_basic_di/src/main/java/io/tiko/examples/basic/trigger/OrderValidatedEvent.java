package io.tiko.examples.basic.trigger;

public record OrderValidatedEvent(long id, boolean valid, double amount) {}
