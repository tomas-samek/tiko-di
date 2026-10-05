package io.tiko.processor.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code @Key} values reach generated binders only as escaped string literals (#479). A key
 * containing a quote, backslash, line break or unicode escape must not break — or inject code
 * into — the generated {@code checkUnknownKeys} call, for top-level and nested records alike.
 */
class KeyLiteralEscapingTest {

    private static final String INJECTION =
            "x\\\")); System.out.println(\\\"INJECTED\\\"); java.util.Set.of(java.util.Set.of(\\\"y";

    static Stream<Arguments> hostileKeys() {
        return Stream.of(
                Arguments.of("quote and backslash", "a\\\"b\\\\c"),
                Arguments.of("line break", "a\\nb"),
                Arguments.of("unicode escape", "a\\\\u0022b"),
                Arguments.of("code injection", INJECTION));
    }

    @ParameterizedTest(name = "top-level record: {0}")
    @MethodSource("hostileKeys")
    void hostileKeyOnTopLevelRecordStaysALiteral(String name, String keyLiteral) throws IOException {
        var c = compile(JavaFileObjects.forSourceLines(
                "io.example.SvcConfig",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "import io.tiko.annotations.Key;",
                "@Configuration(prefix = \"svc\")",
                "public record SvcConfig(@Key(\"" + keyLiteral + "\") int port) {}"));

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generatedSource(c, "SvcConfig")).doesNotContain("System.out.println(\"INJECTED\")");
    }

    @ParameterizedTest(name = "nested record: {0}")
    @MethodSource("hostileKeys")
    void hostileKeyOnNestedRecordStaysALiteral(String name, String keyLiteral) throws IOException {
        var pool = JavaFileObjects.forSourceLines(
                "io.example.Pool",
                "package io.example;",
                "import io.tiko.annotations.Key;",
                "public record Pool(@Key(\"" + keyLiteral + "\") int size) {}");
        var config = JavaFileObjects.forSourceLines(
                "io.example.DbConfig",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"db\")",
                "public record DbConfig(Pool pool) {}");

        var c = compile(pool, config);

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generatedSource(c, "PoolNestedCoercer")).doesNotContain("System.out.println(\"INJECTED\")");
    }

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    private static String generatedSource(Compilation c, String nameFragment) throws IOException {
        for (var f : c.generatedSourceFiles()) {
            if (f.getName().contains(nameFragment)) {
                return f.getCharContent(true).toString();
            }
        }
        throw new AssertionError("no generated source matching " + nameFragment);
    }
}
