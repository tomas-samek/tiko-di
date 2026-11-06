package io.tiko.processor.core;

import java.util.Optional;

/** Reads resources from the compile classpath. On KSP it always returns empty in K1. */
public interface ClasspathProbe {

    /** The resource's text, or empty when it is not on the classpath or cannot be read. */
    Optional<String> readResource(String path);
}
