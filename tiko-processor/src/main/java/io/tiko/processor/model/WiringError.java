package io.tiko.processor.model;

/**
 * Compile-time diagnostic captured for downstream tooling. Persisted to
 * {@code META-INF/tiko/wiring-errors.json} alongside {@code topology.json} so MCP
 * clients (and other consumers) can surface structured wiring failures without
 * scraping the build log.
 *
 * <p>Valid {@code kind} values: {@code MISSING_DEPENDENCY}, {@code CIRCULAR_DEPENDENCY},
 * {@code SCOPE_VIOLATION}, {@code AMBIGUOUS_QUALIFIER}, {@code BAD_PRODUCES}, {@code OTHER}.
 * The {@code sourceFile} and {@code line} fields are best-effort: when the processor
 * cannot derive a source position cheaply, {@code sourceFile} is {@code null} and
 * {@code line} is {@code 0}.
 */
public record WiringError(
        String kind, String sourceFile, int line, String componentFqn, String message, String suggestedFix) {}
