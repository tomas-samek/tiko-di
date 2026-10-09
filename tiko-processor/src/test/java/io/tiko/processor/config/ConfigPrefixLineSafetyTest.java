package io.tiko.processor.config;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code META-INF/tiko/configs.txt} holds one {@code <fqn>=<prefix>} entry per line, and the
 * runtime loads every listed name with {@code Class.forName}. A prefix that could add or alter an
 * entry is rejected at compile time, on the record (SEC-5, #496).
 */
class ConfigPrefixLineSafetyTest {

    static Stream<Arguments> unsafePrefixes() {
        return Stream.of(
                Arguments.of("line break", "svc\\njava.lang.Runtime=x"),
                Arguments.of("carriage return", "svc\\rx"),
                Arguments.of("separator", "svc=x"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafePrefixes")
    void prefixThatCouldAlterConfigsTxtIsACompileError(String name, String javaLiteral) {
        var c = compile(javaLiteral);

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("prefix");
        assertThat(c).hadErrorContaining("SvcConfig");
    }

    @Test
    void ordinaryDottedPrefixStillCompiles() {
        assertThat(compile("tiko.my-app_v2")).succeeded();
    }

    private static com.google.testing.compile.Compilation compile(String javaLiteral) {
        return Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(JavaFileObjects.forSourceLines(
                        "io.example.SvcConfig",
                        "package io.example;",
                        "import io.tiko.annotations.Configuration;",
                        "@Configuration(prefix = \"" + javaLiteral + "\")",
                        "public record SvcConfig(int port) {}"));
    }
}
