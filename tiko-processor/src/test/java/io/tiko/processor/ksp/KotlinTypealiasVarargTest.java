package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A vararg whose element type is a Kotlin typealias of a primitive is the primitive array of its
 * JVM signature, so the generated factory compiles against the class and the program boots with a
 * {@code @Produces} of that array as the dependency.
 */
class KotlinTypealiasVarargTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void varargOfTypealiasCompilesAndBoots(String name, String aliases, String array, String producer)
            throws Exception {
        var graph = KspCompiler.kotlin(
                "Graph.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.Produces",
                "import lib.Element",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Source {",
                "    @Produces fun values(): " + array + " = " + producer,
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Sink(vararg val values: Element) {",
                "    fun size(): Int = values.size",
                "}");
        var library = KspCompiler.kotlin("Aliases.kt", "package lib", "", aliases);

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), library, graph);
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        bootsWithSizeTwo(result);

        var compiledLibrary = KspCompiler.library(KspCompiler.kotlin("Aliases.kt", "package lib", "", aliases));
        result = KspCompiler.compile(
                java.util.List.of(new TikoSymbolProcessorProvider()),
                java.util.Map.of(),
                java.util.List.of(compiledLibrary),
                graph);
        bootsWithSizeTwo(result);
    }

    private static void bootsWithSizeTwo(KspCompiler.KspResult result) throws Exception {

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result)) {
            var sink = program.get("app.Sink");
            assertThat(sink.getClass().getMethod("size").invoke(sink)).isEqualTo(2);
        }
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                Arguments.of("intAlias", "typealias Element = Int", "IntArray", "intArrayOf(1, 2)"),
                Arguments.of("longAlias", "typealias Element = Long", "LongArray", "longArrayOf(1L, 2L)"),
                Arguments.of(
                        "booleanAlias", "typealias Element = Boolean", "BooleanArray", "booleanArrayOf(true, false)"),
                Arguments.of(
                        "aliasOfAlias",
                        "typealias Base = Int\ntypealias Element = Base",
                        "IntArray",
                        "intArrayOf(1, 2)"));
    }
}
