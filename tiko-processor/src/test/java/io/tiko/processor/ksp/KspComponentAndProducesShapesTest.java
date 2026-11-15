package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #746 on the KSP frontend: a Kotlin component or {@code @Produces} shape the generated code
 * cannot compile against is a located error with a suggested fix.
 */
class KspComponentAndProducesShapesTest {

    private static String program(String declarations) {
        return String.join(
                "\n",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.Produces",
                "",
                declarations);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void unsupportedKotlinShapeIsALocatedError(String name, String declarations, List<String> fragments) {
        var result = KspCompiler.compile(
                new TikoSymbolProcessorProvider(), KspCompiler.kotlin("Shapes.kt", program(declarations)));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .contains("Shapes.kt:")
                .contains(fragments)
                .contains("Suggested fixes:")
                .doesNotContain("INTERNAL_ERROR")
                .doesNotContain("Tiko DI processing failed");
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("private component", """
                        @Component(scope = Scope.SINGLETON)
                        private class Hidden
                        """, List.of("@Component app.Hidden must be public")),
                Arguments.of("component with type parameters", """
                        @Component(scope = Scope.SINGLETON)
                        class Box<T>
                        """, List.of("@Component app.Box", "type parameters")),
                Arguments.of(
                        "component that implements a private interface",
                        """
                        private interface Api
                        @Component(scope = Scope.SINGLETON)
                        class Impl : Api
                        """,
                        List.of("app.Impl is routed as app.Api, which is not public", "Make app.Api public")),
                Arguments.of(
                        "@Produces function with type parameters",
                        """
                        class Thing
                        @Component(scope = Scope.SINGLETON)
                        class Cfg {
                            @Produces fun <T> anything(): Thing = Thing()
                        }
                        """,
                        List.of("@Produces method 'app.Cfg.anything'", "type parameters")),
                Arguments.of(
                        "overloaded @Produces functions",
                        """
                        class Thing
                        @Component(scope = Scope.SINGLETON)
                        class Cfg {
                            @Produces(scope = Scope.SINGLETON) fun thing(): Thing = Thing()
                            @Produces(scope = Scope.SINGLETON, name = "second") fun thing(self: Cfg): Thing = Thing()
                        }
                        """,
                        List.of("@Produces methods named 'thing' in app.Cfg", "Rename")));
    }
}
