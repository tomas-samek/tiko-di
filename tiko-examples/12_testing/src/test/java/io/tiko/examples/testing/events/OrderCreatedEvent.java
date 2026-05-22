package io.tiko.examples.testing.events;

import java.time.Instant;

public record OrderCreatedEvent(String txnId, String customerId, long amountCents, Instant at) {}
