package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #746 on the KSP frontend: a Kotlin {@code @EventHandler} whose parameters the generated registry
 * cannot pass is a located error, judged on the Java type the parameter compiles to.
 */
class KspEventHandlerShapesTest {

    private static String listener(String handler) {
        return String.join(
                "\n",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.EventHandler",
                "",
                "class OrderPlaced(val id: String)",
                "class OrderShipped(val id: String)",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Listener {",
                "    " + handler,
                "}");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void unsupportedKotlinHandlerShapeIsALocatedError(String name, String handler, List<String> fragments) {
        var result = KspCompiler.compile(
                new TikoSymbolProcessorProvider(), KspCompiler.kotlin("Listener.kt", listener(handler)));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .contains("Listener.kt:")
                .contains(fragments)
                .contains("Suggested fixes:")
                .doesNotContain("INTERNAL_ERROR")
                .doesNotContain("Tiko DI processing failed");
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of(
                        "Int event parameter",
                        "@EventHandler fun on(count: Int) {}",
                        List.of("@EventHandler 'Listener.on'", "not an event type")),
                Arguments.of(
                        "Array event parameter",
                        "@EventHandler fun on(batch: Array<OrderPlaced>) {}",
                        List.of("@EventHandler 'Listener.on'", "not an event type")),
                Arguments.of(
                        "two payload parameters",
                        "@EventHandler fun on(placed: OrderPlaced, shipped: OrderShipped) {}",
                        List.of("@EventHandler 'Listener.on'", "second parameter", "io.tiko.Event")));
    }

    @Test
    void asyncHandlerThatDeclaresACheckedExceptionCompiles() {
        var result = KspCompiler.compile(
                new TikoSymbolProcessorProvider(),
                KspCompiler.kotlin(
                        "Listener.kt",
                        listener("@Throws(java.io.IOException::class) @EventHandler(async = true)"
                                + " fun on(placed: OrderPlaced) {}")));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
    }
}
