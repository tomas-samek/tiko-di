package io.tiko.processor.core.ksp;

import static io.tiko.processor.core.ksp.KspFacts.cls;
import static io.tiko.processor.core.ksp.KspFacts.fact;
import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.ksp.KspFacts.Fact;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #677 on KSP: a member value the source wrote is told from the annotation type's default, also
 * when the written value equals the default. One row per case, for an annotation declared in
 * Kotlin and for one that comes from a class file ({@code java.lang.Deprecated}).
 */
class KspExplicitAnnotationValueTest {

    private static final String KOTLIN = """
            package e

            annotation class Mode(val async: Boolean = false, val name: String = "n")

            @Mode class Defaulted
            @Mode(async = false) class WrittenLikeTheDefault
            @Mode(async = true) class Written

            @java.lang.Deprecated class JavaDefaulted
            @java.lang.Deprecated(forRemoval = false) class JavaWrittenLikeTheDefault
            """;

    private static Map<String, Object> results;

    @BeforeAll
    static void compile() {
        results = KspFacts.collect(facts(), Map.of(), KspCompiler.kotlin("Explicit.kt", KOTLIN));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("facts")
    void aWrittenValueIsToldFromADefault(Fact fact) {
        assertThat(results).containsEntry(fact.name(), fact.expected());
    }

    static List<Fact> facts() {
        return List.of(
                fact("defaultIsNotExplicit", env -> explicit(env, "e.Defaulted", "e.Mode", "async"), false),
                fact(
                        "writtenLikeTheDefaultIsExplicit",
                        env -> explicit(env, "e.WrittenLikeTheDefault", "e.Mode", "async"),
                        true),
                fact("writtenIsExplicit", env -> explicit(env, "e.Written", "e.Mode", "async"), true),
                fact(
                        "anotherMemberOfTheSameAnnotationIsNotExplicit",
                        env -> explicit(env, "e.Written", "e.Mode", "name"),
                        false),
                fact(
                        "theDefaultStillReadsAsAValue",
                        env -> cls(env, "e.Defaulted")
                                .annotation("e.Mode")
                                .orElseThrow()
                                .bool("async"),
                        false),
                fact(
                        "javaAnnotationDefaultIsNotExplicit",
                        env -> explicit(env, "e.JavaDefaulted", "java.lang.Deprecated", "forRemoval"),
                        false),
                fact(
                        "javaAnnotationWrittenLikeTheDefaultIsExplicit",
                        env -> explicit(env, "e.JavaWrittenLikeTheDefault", "java.lang.Deprecated", "forRemoval"),
                        true));
    }

    private static boolean explicit(ProcessingEnv env, String type, String annotation, String member) {
        return cls(env, type).annotation(annotation).orElseThrow().isExplicit(member);
    }
}
