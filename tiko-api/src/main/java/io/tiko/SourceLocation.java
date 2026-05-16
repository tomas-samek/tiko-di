package io.tiko;

/**
 * Source location of a configuration value, exposed via
 * {@link ConfigSource#locations()}. Best-effort: a missing or unknown
 * location is represented by the absence of an entry in the map, not
 * by a sentinel value.
 *
 * @param source  the source identifier (typically a file name like
 *     {@code "config.yaml"}, or whatever label
 *     {@code ConfigSources.classpath(name)} chose)
 * @param line    1-based line number of the value (or the closest
 *     enclosing structural marker — e.g., the section header for a
 *     missing required key inside that section)
 * @param column  1-based column number, same anchoring rule
 */
public record SourceLocation(String source, int line, int column) {}
