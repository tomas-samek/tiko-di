package io.tiko.examples.basic.teardown;

/**
 * Sentinel output type used only by {@link ThrowingCheckedProducesFactory} to
 * give the failing {@code @Produces} factory a unique return type the test
 * can resolve via {@code container.get(CheckedProducesOutput.class)} without
 * colliding with anything else in the example module.
 */
public final class CheckedProducesOutput {
    public CheckedProducesOutput() {}
}
