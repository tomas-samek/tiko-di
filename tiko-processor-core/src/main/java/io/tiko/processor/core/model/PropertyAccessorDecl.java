package io.tiko.processor.core.model;

/**
 * A Kotlin property accessor that carries an annotation through a {@code @get:} / {@code @set:}
 * use-site target. Java has none, so javac never reports one. Tiko does not inject through
 * accessors; this exists so DI can reject them with a located error instead of ignoring them.
 *
 * @param propertyName the property's source name
 * @param setter whether this is the setter ({@code @set:}) rather than the getter ({@code @get:})
 * @param location the accessor's position (its property's, when the accessor is not written out)
 */
public record PropertyAccessorDecl(String propertyName, boolean setter, Location location) {}
