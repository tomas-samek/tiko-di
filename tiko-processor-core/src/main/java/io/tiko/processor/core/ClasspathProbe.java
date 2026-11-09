package io.tiko.processor.core;

import io.tiko.processor.core.model.ClassDecl;
import java.util.List;
import java.util.Optional;

/**
 * Reads the compile classpath. Each frontend answers one of the two questions: {@link #readResource}
 * is always empty on KSP, and {@link #compiledClassesIn} is always empty on javac.
 */
public interface ClasspathProbe {

    /** The resource's text, or empty when it is not on the classpath or cannot be read. */
    Optional<String> readResource(String path);

    /**
     * The top-level classes of the package that come from the compile classpath, never from this
     * round's sources, sorted by qualified name. Empty for an unknown package.
     */
    List<ClassDecl> compiledClassesIn(String packageName);
}
