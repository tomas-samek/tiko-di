package io.tiko.processor.core.ksp;

import static io.tiko.processor.core.ksp.KspFacts.fact;
import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.processor.core.model.ClassDecl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code ClasspathProbe.compiledClassesIn} on KSP: library classes only, never this round's sources. */
class KspCompiledClassesTest {

    private static final String LIBRARY = """
            package lib

            class B

            class A
            """;

    private static final String SOURCE = """
            package lib

            class C
            """;

    @Test
    void listsLibraryClassesOfThePackageSortedAndNotTheRoundsSources() {
        var results = KspFacts.collect(
                List.of(
                        fact(
                                "known",
                                env -> env.classpath().compiledClassesIn("lib").stream()
                                        .map(ClassDecl::qualifiedName)
                                        .toList(),
                                List.of("lib.A", "lib.B")),
                        fact("unknown", env -> env.classpath().compiledClassesIn("nope.nothing"), List.of())),
                Map.of(),
                List.of(KspCompiler.library(KspCompiler.kotlin("Lib.kt", LIBRARY))),
                KspCompiler.kotlin("C.kt", SOURCE));

        assertThat(results).containsEntry("known", List.of("lib.A", "lib.B"));
        assertThat(results).containsEntry("unknown", List.of());
    }
}
