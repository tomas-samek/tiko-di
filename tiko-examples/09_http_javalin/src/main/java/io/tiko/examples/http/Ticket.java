package io.tiko.examples.http;

import java.time.Instant;
import java.util.UUID;

/** Domain record: the canonical post-create representation of a ticket. */
public record Ticket(UUID id, String title, Instant createdAt) {}
