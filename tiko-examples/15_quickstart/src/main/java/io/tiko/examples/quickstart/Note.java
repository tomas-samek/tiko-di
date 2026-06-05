package io.tiko.examples.quickstart;

import java.time.Instant;
import java.util.UUID;

public record Note(UUID id, String text, Instant createdAt) {}
