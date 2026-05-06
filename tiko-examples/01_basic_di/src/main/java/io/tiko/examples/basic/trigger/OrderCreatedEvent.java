package io.tiko.examples.basic.trigger;

public record OrderCreatedEvent(long id, double amount) {}
