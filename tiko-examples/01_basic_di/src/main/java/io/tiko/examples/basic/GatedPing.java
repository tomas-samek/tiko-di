package io.tiko.examples.basic;

/** Test-fixture event handled by {@link GatedAsyncRecorder}; used by {@code OverflowThrowTest}. */
public record GatedPing(int number) {}
