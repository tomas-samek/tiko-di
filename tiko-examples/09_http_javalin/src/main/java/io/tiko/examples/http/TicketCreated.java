package io.tiko.examples.http;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published after a successful ticket creation. Carries the
 * server-assigned identity plus the per-request correlation ID so async
 * subscribers can read it off-thread (the request's unit of work has torn down
 * by then).
 */
public record TicketCreated(UUID id, String title, String requestId, Instant createdAt) {}
