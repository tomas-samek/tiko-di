package io.tiko.examples.basic;

/**
 * Simple event using Java record.
 * Published when a new message is created.
 */
public record MessageCreatedEvent(Long messageId, String content, String userId) {}
