package io.tiko.processor.core.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.TypeRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Model objects made in one KSP round stay usable, and equal to their re-found selves, in the
 * next. KSP2 invalidates a round's symbols once it has generated files, so the probe generates a
 * file in round one, keeps model objects from it, and reads them in round two.
 */
class KspRoundsTest {

    private static final String KOTLIN = """
            package r

            annotation class Mark(val value: String)

            @Mark("kept")
            class Kept(val name: String) {
                var count: Int = 0
                fun greet(who: String): String = who
            }

            @Mark("top")
            fun helper(times: Int) {}
            """;

    /** What round one handed over, read again in round two. */
    record Kept(
            ClassDecl type,
            MethodDecl method,
            ParamDecl parameter,
            MethodDecl constructor,
            FieldDecl field,
            AnnotationRef annotation,
            TypeRef parameterType,
            MethodDecl topLevel) {}

    /** One fact read in round two from what round one kept. */
    record RoundFact(String name, BiFunction<Kept, ProcessingEnv, Object> read, Object expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Map<String, Object> results = new LinkedHashMap<>();

    @BeforeAll
    static void compile() {
        KspCompiler.compile(List.of(new KeepAcrossRounds()), Map.of(), KspCompiler.kotlin("Kept.kt", KOTLIN));
        assertThat(results).as("round two ran").isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("facts")
    void modelObjectsOutliveTheirRound(RoundFact fact) {
        assertThat(results.get(fact.name())).isEqualTo(fact.expected());
    }

    static List<RoundFact> facts() {
        return List.of(
                new RoundFact(
                        "classEqualsItsReFoundSelf", (kept, env) -> kept.type().equals(type(env)), true),
                new RoundFact("classStillReadable", (kept, env) -> kept.type().qualifiedName(), "r.Kept"),
                new RoundFact(
                        "classMembersStillReadable",
                        (kept, env) -> kept.type().methods().stream()
                                .map(MethodDecl::name)
                                .toList(),
                        List.of("greet")),
                new RoundFact(
                        "methodEqualsItsReFoundSelf",
                        (kept, env) -> kept.method().equals(type(env).methods().get(0)),
                        true),
                new RoundFact(
                        "methodStillReadable", (kept, env) -> kept.method().jvmName(), "greet"),
                new RoundFact(
                        "parameterEqualsItsReFoundSelf",
                        (kept, env) -> kept.parameter()
                                .equals(type(env).methods().get(0).parameters().get(0)),
                        true),
                new RoundFact(
                        "parameterStillReadable",
                        (kept, env) -> kept.parameter().name(),
                        "who"),
                new RoundFact(
                        "parameterTypeStillReadable",
                        (kept, env) -> kept.parameterType().displayName(),
                        "java.lang.String"),
                new RoundFact(
                        "fieldEqualsItsReFoundSelf",
                        (kept, env) -> kept.field().equals(type(env).fields().get(1)),
                        true),
                new RoundFact(
                        "fieldStillReadable", (kept, env) -> kept.field().type().displayName(), "int"),
                new RoundFact(
                        "annotationEqualsItsReFoundSelf",
                        (kept, env) -> kept.annotation()
                                .equals(type(env).annotation("r.Mark").orElseThrow()),
                        true),
                new RoundFact(
                        "annotationValuesReReadInTheLaterRound",
                        (kept, env) -> kept.annotation().string("value"),
                        "kept"),
                new RoundFact(
                        "annotationLocationStillUsable",
                        (kept, env) -> kept.annotation().location().enclosingClassName(),
                        Optional.of("r.Kept")),
                new RoundFact(
                        "topLevelFunctionStillReadable",
                        (kept, env) ->
                                kept.topLevel().parameters().get(0).type().displayName(),
                        "int"),
                new RoundFact(
                        "constructorReturnTypeStillVoid",
                        (kept, env) -> kept.constructor().returnType().isVoid(),
                        true),
                new RoundFact(
                        "laterRoundFindsTheGeneratedClass",
                        (kept, env) -> env.types().findClass("r.Later").isPresent(),
                        true));
    }

    private static ClassDecl type(ProcessingEnv env) {
        return env.types().findClass("r.Kept").orElseThrow();
    }

    /** Keeps model objects from round one, generates a file to force round two, reads them there. */
    private static final class KeepAcrossRounds implements SymbolProcessorProvider {

        @Override
        public SymbolProcessor create(com.google.devtools.ksp.processing.SymbolProcessorEnvironment environment) {
            return new SymbolProcessor() {
                private KspProcessingEnv env;
                private Kept kept;

                @Override
                public List<KSAnnotated> process(com.google.devtools.ksp.processing.Resolver resolver) {
                    if (env == null) {
                        env = KspProcessingEnv.of(
                                resolver,
                                environment.getCodeGenerator(),
                                environment.getLogger(),
                                environment.getOptions());
                        kept = keep(env);
                        generateLater(environment);
                    } else if (results.isEmpty()) {
                        var next = env.nextRound(resolver);
                        facts().forEach(fact -> results.put(fact.name(), read(fact, next)));
                    }
                    return List.of();
                }

                private Object read(RoundFact fact, ProcessingEnv next) {
                    try {
                        return fact.read().apply(kept, next);
                    } catch (RuntimeException e) {
                        return e;
                    }
                }
            };
        }

        private static Kept keep(ProcessingEnv env) {
            var type = type(env);
            var method = type.methods().get(0);
            return new Kept(
                    type,
                    method,
                    method.parameters().get(0),
                    type.primaryConstructor().orElseThrow(),
                    type.fields().get(1),
                    type.annotation("r.Mark").orElseThrow(),
                    method.parameters().get(0).type(),
                    env.methodsAnnotatedWith("r.Mark").get(0));
        }

        private static void generateLater(com.google.devtools.ksp.processing.SymbolProcessorEnvironment environment) {
            try (var out = environment.getCodeGenerator().createNewFile(new Dependencies(false), "r", "Later", "kt")) {
                out.write("package r\n\nclass Later\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
