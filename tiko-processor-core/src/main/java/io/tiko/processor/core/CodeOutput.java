package io.tiko.processor.core;

import com.palantir.javapoet.JavaFile;
import io.tiko.processor.core.model.ClassDecl;
import java.io.IOException;
import java.util.List;

/** Writes generated sources and resources, linked to the declarations they were derived from. */
public interface CodeOutput {

    /**
     * Writes a generated Java source. {@code aggregating} marks output derived from many inputs
     * (incremental-processing hint for frontends that use it).
     */
    void writeJava(JavaFile file, List<ClassDecl> originating, boolean aggregating) throws IOException;

    /** Writes a resource file at {@code path}, relative to the class output root. */
    void writeResource(String path, String content, List<ClassDecl> originating) throws IOException;
}
