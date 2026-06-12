package io.tiko.kafka.runtime.fixtures;

/** Event consumed only by {@link GatedAsyncRecorder} — no other test publishes it. */
public record GatedPing(String id) {}
