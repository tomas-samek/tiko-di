package io.tiko.processor.core.model;

/**
 * An opaque source position. Only {@link io.tiko.processor.core.Diagnostics} reads it; each
 * frontend adapter wraps its own element type behind it.
 */
public interface Location {}
