package io.tiko.runtime;

/**
 * Test fixture interface resolvable through {@link StubContainer} by interface key only —
 * it never appears in {@code components.txt}, mirroring how generated containers route
 * interface-typed lookups that the aggregator's concrete-class map can't see (#335).
 */
public interface StubService {}
