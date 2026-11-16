package io.tiko.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.tools.JavaFileObject;

/** Compile-testing helpers for tests that compile small sources with the DI processor and read what it generated. */
public final class CompileTesting {

    private CompileTesting() {}

    /**
     * A source file {@code demo.<name>}: the package line, then {@code imports} on one line, then
     * {@code lines}.
     */
    public static JavaFileObject source(String imports, String name, String... lines) {
        var all = new String[lines.length + 2];
        all[0] = "package demo;";
        all[1] = imports;
        System.arraycopy(lines, 0, all, 2, lines.length);
        return JavaFileObjects.forSourceLines("demo." + name, all);
    }

    /** Compiles {@code sources} with the DI processor, without asserting the outcome. */
    public static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    /** Like {@link #compile(JavaFileObject...)}, with compiler options such as {@code -Atiko.profiles=prod}. */
    public static Compilation compile(List<String> options, JavaFileObject... sources) {
        return Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions(options)
                .compile(sources);
    }

    /** The text of the first generated source whose file name contains {@code nameFragment}. */
    public static String generated(Compilation compilation, String nameFragment) throws IOException {
        JavaFileObject file = compilation.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains(nameFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(nameFragment + " was not generated; generated sources: "
                        + compilation.generatedSourceFiles().stream()
                                .map(JavaFileObject::getName)
                                .toList()));
        try (var content = file.openInputStream()) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
