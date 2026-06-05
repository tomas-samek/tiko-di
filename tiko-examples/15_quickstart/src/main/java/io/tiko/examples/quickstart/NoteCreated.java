package io.tiko.examples.quickstart;

import java.time.Instant;
import java.util.UUID;

public record NoteCreated(UUID id, Instant at) {}
