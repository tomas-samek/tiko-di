package io.tiko.examples.multimodule.modulea;

/**
 * A way to reach a user. Declared here and implemented in both modules, so that
 * {@code Picker<Channel>} has something to list across the module boundary.
 */
public interface Channel {

    String name();
}
