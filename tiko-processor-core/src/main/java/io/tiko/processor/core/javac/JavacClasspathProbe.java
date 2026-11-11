package io.tiko.processor.core.javac;

import io.tiko.processor.core.ClasspathProbe;
import io.tiko.processor.core.model.ClassDecl;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import javax.annotation.processing.Filer;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.StandardLocation;

/**
 * {@link ClasspathProbe} over {@link Filer#getResource}: a missing or unreadable resource
 * ({@code IOException}, including {@code FilerException}) reads as empty. Class names come from
 * {@link Elements#getPackageElement}, which merges the package across every classpath entry.
 */
final class JavacClasspathProbe implements ClasspathProbe {

    private final Filer filer;
    private final Elements elements;

    JavacClasspathProbe(Filer filer, Elements elements) {
        this.filer = filer;
        this.elements = elements;
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

    @Override
    public List<String> simpleClassNamesIn(String packageName) {
        var packageElement = elements.getPackageElement(packageName);
        if (packageElement == null) {
            return List.of();
        }
        return ElementFilter.typesIn(packageElement.getEnclosedElements()).stream()
                .map(type -> type.getSimpleName().toString())
                .toList();
    }
}
