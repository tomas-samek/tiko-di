package io.tiko.processor.core.javac;

import io.tiko.processor.core.ClasspathProbe;
import io.tiko.processor.core.model.ClassDecl;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import javax.annotation.processing.Filer;
import javax.tools.StandardLocation;

/**
 * {@link ClasspathProbe} over {@link Filer#getResource}: a missing or unreadable resource
 * ({@code IOException}, including {@code FilerException}) reads as empty.
 */
final class JavacClasspathProbe implements ClasspathProbe {

    private final Filer filer;

    JavacClasspathProbe(Filer filer) {
        this.filer = filer;
    }

    @Override
    public Optional<String> readResource(String path) {
        try {
            var resource = filer.getResource(StandardLocation.CLASS_PATH, "", path);
            try (var reader = resource.openReader(true)) {
                var text = new StringWriter();
                reader.transferTo(text);
                return Optional.of(text.toString());
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Always empty: javac reads the resources instead. */
    @Override
    public List<ClassDecl> compiledClassesIn(String packageName) {
        return List.of();
    }
}
