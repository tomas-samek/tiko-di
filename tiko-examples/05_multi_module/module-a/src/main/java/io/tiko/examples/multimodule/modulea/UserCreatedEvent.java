package io.tiko.examples.multimodule.modulea;

/**
 * Event published when a user is created.
 */
public record UserCreatedEvent(Long userId, String name, String email) {
}
