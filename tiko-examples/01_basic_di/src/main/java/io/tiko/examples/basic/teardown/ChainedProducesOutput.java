package io.tiko.examples.basic.teardown;

/**
 * What {@link ChainedProducesFactory} produces from a {@link CheckedProducesOutput}, whose own
 * factory fails. Marker type so {@code container.get(ChainedProducesOutput.class)} routes to that
 * one factory.
 */
public final class ChainedProducesOutput {}
