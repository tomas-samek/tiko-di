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
 * The Kotlin construct rule (spec, K1 point 4): a construct that maps 1:1 onto a Java construct
 * Tiko supports is supported, one that would add DI semantics is rejected with a Tiko-format
 * error. Each row compiles one Kotlin file on KSP next to an always-valid {@code Anchor}
 * component (so validation runs and {@code wiring-errors.json} is written). A supported row
 * expects a clean build whose generated Java contains the fragments; a rejected row expects the
 * error kind in {@code wiring-errors.json} and every fragment in the compiler output.
 */
class KotlinConstructRulesTest {

    private static final String HEADER = """
            package app

            import io.tiko.Scope
            import io.tiko.annotations.Component
            import io.tiko.annotations.EventHandler
            import io.tiko.annotations.Named
            import io.tiko.annotations.PostConstruct
            import io.tiko.annotations.Produces

            class Clock
            class Ping
            """;

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void appliesTheKotlinConstructRule(
            String name, String kotlinSource, boolean expectedOk, String expectedKind, List<String> fragments) {
        var result = KspCompiler.compile(
                new TikoSymbolProcessorProvider(),
                KspCompiler.kotlin("Anchor.kt", "package app", "@io.tiko.annotations.Component class Anchor"),
                source(kotlinSource));

        if (expectedOk) {
            assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
            assertThat(String.join("\n", result.javaSources().values())).contains(fragments);
        } else {
            WiringErrorsJson.assertRejected(result, expectedKind, fragments);
        }
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                supported(
                        "finalClassComponentIsSupported",
                        """
                        @Component(scope = Scope.SINGLETON) class Repository
                        @Component(scope = Scope.SINGLETON) class Service(val repository: Repository)
                        """,
                        "() -> container.getRepository())",
                        "new Service(repository)"),
                supported(
                        "fieldUseSiteNamedIsSupported",
                        """
                        interface Store
                        @Component(scope = Scope.SINGLETON, name = "x") class RedStore : Store
                        @Component(scope = Scope.SINGLETON, name = "y") class BlueStore : Store
                        @Component(scope = Scope.SINGLETON) class User(@param:Named("x") val store: Store)
                        """,
                        "() -> container.get(app.Store.class, \"x\"))",
                        "new User(store)"),
                supported(
                        "internalComponentIsSupported",
                        """
                        @Component(scope = Scope.SINGLETON) internal class Repository
                        @Component(scope = Scope.SINGLETON) internal class Service(val repository: Repository)
                        """,
                        "() -> container.getRepository())",
                        "new Service(repository)"),
                supported("defaultParameterStillInjected", """
                        @Component(scope = Scope.SINGLETON) class B
                        @Component(scope = Scope.SINGLETON) class A(val b: B = B())
                        """, "() -> container.getB())", "new A(b)"),
                supported("primaryConstructorInjectedOverSecondary", """
                        @Component(scope = Scope.SINGLETON) class B
                        @Component(scope = Scope.SINGLETON) class A(val b: B) {
                            constructor() : this(B())
                        }
                        """, "() -> container.getB())", "new A(b)"),
                supported("internalPrimaryConstructorIsInjected", """
                        @Component(scope = Scope.SINGLETON) class B
                        @Component(scope = Scope.SINGLETON) class A internal constructor(val b: B)
                        """, "() -> container.getB())", "new A(b)"),
                rejected(
                        "topLevelProducesRejected",
                        "@Produces fun clock(): Clock = Clock()",
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "@Produces on a top-level function",
                        "move it into a @Component class"),
                rejected(
                        "topLevelEventHandlerRejected",
                        "@EventHandler fun onPing(event: Ping) {}",
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "@EventHandler on a top-level function",
                        "move it into a @Component class"),
                rejected(
                        "objectComponentRejected",
                        "@Component(scope = Scope.SINGLETON) object Registry",
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "object declarations can't be components",
                        "use a class with scope = SINGLETON"),
                rejected(
                        "companionObjectComponentRejected",
                        """
                        class Host {
                            @Component(scope = Scope.SINGLETON) companion object
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "Host.Companion is a companion object: object declarations can't be components",
                        "use a class with scope = SINGLETON"),
                rejected(
                        "companionEventHandlerRejected",
                        """
                        @Component(scope = Scope.SINGLETON) class Listener {
                            companion object {
                                @EventHandler fun onPing(event: Ping) {}
                            }
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "@EventHandler inside a companion object",
                        "move it to the enclosing @Component class"),
                rejected(
                        "extensionProducesRejected",
                        """
                        class Config
                        @Component(scope = Scope.SINGLETON) class Factories {
                            @Produces fun Config.clock(): Clock = Clock()
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "@Produces on the extension function 'Factories.clock'",
                        "declare a regular fun that takes the receiver as a parameter"),
                rejected(
                        "companionProducesRejected",
                        """
                        @Component(scope = Scope.SINGLETON) class Host {
                            companion object {
                                @Produces fun clock(): Clock = Clock()
                            }
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "@Produces inside a companion object",
                        "move it to the enclosing @Component class"),
                rejected(
                        "objectProducesRejected",
                        """
                        object Factories {
                            @Produces fun clock(): Clock = Clock()
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "@Produces inside an object declaration",
                        "move it into a @Component class"),
                rejected(
                        "objectEventHandlerRejected",
                        """
                        object Listeners {
                            @EventHandler fun onPing(event: Ping) {}
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "@EventHandler inside an object declaration",
                        "move it into a @Component class"),
                rejected(
                        "suspendEventHandlerRejectedUntilK2",
                        """
                        @Component(scope = Scope.SINGLETON) class Listener {
                            @EventHandler suspend fun onPing(event: Ping) {}
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "suspend @EventHandler is not supported yet",
                        "make it a regular fun, or wait for coroutine support (K2)"),
                rejected(
                        "suspendProducesRejected",
                        """
                        @Component(scope = Scope.SINGLETON) class Factories {
                            @Produces suspend fun clock(): Clock = Clock()
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "suspend @Produces is not supported",
                        "make it a regular fun"),
                rejected(
                        "getterProducesRejected",
                        """
                        @Component(scope = Scope.SINGLETON) class Factories {
                            @get:Produces val clock: Clock get() = Clock()
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "@Produces on the getter of property 'clock'",
                        "declare a fun instead"),
                rejected(
                        "setterPostConstructRejected",
                        """
                        @Component(scope = Scope.SINGLETON) class Holder {
                            @set:PostConstruct var ready: Boolean = false
                        }
                        """,
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        "@PostConstruct on the setter of property 'ready'",
                        "declare a fun instead"),
                rejected(
                        "varargConstructorParameterBehavesAsOnJavac",
                        """
                        @Component(scope = Scope.SINGLETON) class B
                        @Component(scope = Scope.SINGLETON) class A(vararg val bs: B)
                        """,
                        ErrorReporter.KIND_MISSING_DEPENDENCY,
                        "Cannot resolve dependency: app.B[]"));
    }

    /**
     * A Java varargs constructor compiled through KSP is keyed by its array type, as javac keys it
     * ({@code demo.B[]} on javac), so it is the same missing dependency on both frontends.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("javaVarargs")
    void javaVarargsOnKspAreKeyedByTheirArrayType(String name, String parameter, String expectedMessage) {
        var result = KspCompiler.compile(
                new TikoSymbolProcessorProvider(),
                KspCompiler.kotlin("Anchor.kt", "package app", "@io.tiko.annotations.Component class Anchor"),
                KspCompiler.kotlin("B.kt", "package app", "@io.tiko.annotations.Component class B"),
                KspCompiler.java(
                        "app/A.java",
                        "package app;",
                        "@io.tiko.annotations.Component",
                        "public class A {",
                        "  public A(" + parameter + ") {}",
                        "}"));

        WiringErrorsJson.assertRejected(result, ErrorReporter.KIND_MISSING_DEPENDENCY, List.of(expectedMessage));
    }

    static Stream<Arguments> javaVarargs() {
        return Stream.of(
                Arguments.of("classVarargs", "B... bs", "Cannot resolve dependency: app.B[]"),
                Arguments.of("boxedVarargs", "Integer... n", "Cannot resolve dependency: java.lang.Integer[]"));
    }

    private static Arguments supported(String name, String body, String... generatedFragments) {
        return Arguments.of(name, HEADER + body, true, null, List.of(generatedFragments));
    }

    private static Arguments rejected(String name, String body, String kind, String... messageFragments) {
        return Arguments.of(name, HEADER + body, false, kind, List.of(messageFragments));
    }

    private static SourceFile source(String kotlinSource) {
        return KspCompiler.kotlin("Construct.kt", kotlinSource);
    }
}
