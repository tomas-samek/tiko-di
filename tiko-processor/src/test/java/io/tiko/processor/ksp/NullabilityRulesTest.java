package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import com.tschuchort.compiletesting.SourceFile;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.util.ErrorReporter;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Nullability between a provider and an injection point must match exactly (spec, K1 point 4):
 * a nullable {@code @Produces} only into {@code T?} points, a non-null provider only into
 * {@code T} points; Java declarations count as non-null, and {@code T?} never makes a dependency
 * optional. Each row is one KSP compile; a rejected row expects the error kind in
 * {@code wiring-errors.json} and every fragment in the compiler output.
 */
class NullabilityRulesTest {

    private static final String HEADER = """
            package app

            import io.tiko.Provider
            import io.tiko.Scope
            import io.tiko.annotations.Component
            import io.tiko.annotations.Produces

            class Foo
            """;

    private static final String NULLABLE_PRODUCER = """
            @Component(scope = Scope.SINGLETON)
            class Producers {
                @Produces(scope = Scope.SINGLETON)
                fun foo(): Foo? = null
            }
            """;

    private static final String NON_NULL_PRODUCER = """
            @Component(scope = Scope.SINGLETON)
            class Producers {
                @Produces(scope = Scope.SINGLETON)
                fun foo(): Foo = Foo()
            }
            """;

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void matchesNullabilityExactly(String name, List<SourceFile> sources, String expectedKind, List<String> fragments) {
        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), sources.toArray(SourceFile[]::new));

        if (expectedKind == null) {
            assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        } else {
            WiringErrorsJson.assertRejected(result, expectedKind, fragments);
        }
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                ok(
                        "nullableProducerIntoNullablePoint",
                        kotlin(NULLABLE_PRODUCER + "@Component(scope = Scope.SINGLETON) class User(val foo: Foo?)")),
                ok(
                        "nullableProducerIntoNullableProviderPoint",
                        kotlin(NULLABLE_PRODUCER
                                + "@Component(scope = Scope.SINGLETON) class User(val foo: Provider<Foo?>)")),
                rejected(
                        "nullableProducerIntoNonNullPoint",
                        List.of(kotlin(
                                NULLABLE_PRODUCER + "@Component(scope = Scope.SINGLETON) class User(val foo: Foo)")),
                        ErrorReporter.NULLABILITY_MISMATCH,
                        "Producers.foo",
                        "declare the parameter as `Foo?`"),
                rejected(
                        "nonNullProducerIntoNullablePoint",
                        List.of(kotlin(
                                NON_NULL_PRODUCER + "@Component(scope = Scope.SINGLETON) class User(val foo: Foo?)")),
                        ErrorReporter.NULLABILITY_MISMATCH,
                        "Producers.foo",
                        "declare the parameter as `Foo`, not `Foo?`"),
                rejected(
                        "nullablePointWithoutProviderIsMissingDependency",
                        List.of(kotlin("@Component(scope = Scope.SINGLETON) class User(val foo: Foo?)")),
                        ErrorReporter.KIND_MISSING_DEPENDENCY,
                        "Cannot resolve dependency: app.Foo"),
                rejected(
                        "javaComponentIntoNullableKotlinPoint",
                        List.of(
                                KspCompiler.java(
                                        "app/Gateway.java",
                                        "package app;",
                                        "import io.tiko.Scope;",
                                        "import io.tiko.annotations.Component;",
                                        "@Component(scope = Scope.SINGLETON)",
                                        "public class Gateway {}"),
                                kotlin("@Component(scope = Scope.SINGLETON) class User(val gateway: Gateway?)")),
                        ErrorReporter.NULLABILITY_MISMATCH,
                        "Gateway",
                        "declare the parameter as `Gateway`, not `Gateway?`"),
                rejected(
                        "kotlinNullableProducerIntoJavaPoint",
                        List.of(
                                kotlin(NULLABLE_PRODUCER),
                                KspCompiler.java(
                                        "app/JavaUser.java",
                                        "package app;",
                                        "import io.tiko.Scope;",
                                        "import io.tiko.annotations.Component;",
                                        "@Component(scope = Scope.SINGLETON)",
                                        "public class JavaUser {",
                                        "  public JavaUser(Foo foo) {}",
                                        "}")),
                        ErrorReporter.NULLABILITY_MISMATCH,
                        "Producers.foo",
                        "make the producer non-null"),
                rejected(
                        "nullableEventProducerIntoSingletonRejected",
                        List.of(kotlin("""
                                interface Session
                                @Component(scope = Scope.SINGLETON)
                                class Sessions {
                                    @Produces(scope = Scope.EVENT)
                                    fun session(): Session? = null
                                }
                                @Component(scope = Scope.SINGLETON) class User(val session: Session?)
                                """)),
                        ErrorReporter.NULLABILITY_MISMATCH,
                        "Sessions.session",
                        "proxy",
                        "make the producer non-null"));
    }

    private static Arguments ok(String name, SourceFile... sources) {
        return Arguments.of(name, List.of(sources), null, List.of());
    }

    private static Arguments rejected(String name, List<SourceFile> sources, String kind, String... fragments) {
        return Arguments.of(name, sources, kind, List.of(fragments));
    }

    private static SourceFile kotlin(String body) {
        return KspCompiler.kotlin("Graph.kt", HEADER + body);
    }
}
