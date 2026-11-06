package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.util.ErrorReporter;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #589 on KSP: a component rejected as an unsupported Kotlin construct is the build's only error;
 * what only follows from its absence (a consumer's missing dependency, a handler or an instance
 * {@code @Produces} on it) is not reported on top of it.
 */
class KspRejectedComponentCascadeTest {

    private static final String HEADER = """
            package app

            import io.tiko.Scope
            import io.tiko.annotations.Component
            import io.tiko.annotations.EventHandler
            import io.tiko.annotations.Produces

            class Ping
            class Widget
            """;

    static Stream<Arguments> rows() {
        return Stream.of(
                Arguments.of(
                        "objectComponent x consumer",
                        """
                        @Component(scope = Scope.SINGLETON) object Registry
                        @Component(scope = Scope.SINGLETON) class Service(val registry: Registry)
                        """,
                        "Registry is an object declaration: object declarations can't be components"),
                Arguments.of(
                        "valueClassComponent x eventHandler",
                        """
                        @JvmInline @Component(scope = Scope.SINGLETON) value class Tenant(val raw: String) {
                            @EventHandler fun onPing(event: Ping) {}
                        }
                        """,
                        "Tenant is a Kotlin value class: value classes can't be components"),
                Arguments.of(
                        "valueClassComponent x instanceProduces",
                        """
                        @JvmInline @Component(scope = Scope.SINGLETON) value class Tenant(val raw: String) {
                            @Produces fun widget(): Widget = Widget()
                        }
                        """,
                        "Tenant is a Kotlin value class: value classes can't be components"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void rejectedKotlinComponentReportsOnlyItsOwnError(String name, String kotlinSource, String error) {
        var result = KspCompiler.compile(
                new TikoSymbolProcessorProvider(),
                KspCompiler.kotlin("Anchor.kt", "package app", "@io.tiko.annotations.Component class Anchor"),
                KspCompiler.kotlin("Construct.kt", HEADER + kotlinSource));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(WiringErrorsJson.errors(result))
                .as(result.messages())
                .singleElement()
                .satisfies(only -> {
                    assertThat(only).containsEntry("kind", ErrorReporter.KOTLIN_UNSUPPORTED);
                    assertThat(String.valueOf(only.get("message"))).contains(error);
                });
    }
}
