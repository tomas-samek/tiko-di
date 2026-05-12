package io.tiko.kafka.runtime.fixtures;

public record AuditRecorded(String id, String action, String correlationId) {}
