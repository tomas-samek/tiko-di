package io.tiko.processor.config;

import static io.tiko.processor.CompileTesting.compile;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.JavaFileObjects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The processor looks a {@code @Configuration} field's type up to ask whether a coercer exists for
 * it. That lookup must not run the type's static initializer inside the compiler (SEC-8, #810).
 * Each case has a probe type of its own, because the JVM initializes a class once.
 */
class ConfigTypeNotInitializedTest {

    static Stream<Arguments> fieldsOfAProbeType() {
        return Stream.of(
                Arguments.of("plain field", "io.tiko.processor.config.PlainFieldProbe", PlainFieldProbe.MARK, ""),
                Arguments.of(
                        "field with @Default",
                        "io.tiko.processor.config.DefaultedFieldProbe",
                        DefaultedFieldProbe.MARK,
                        "@Default(\"x\") "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldsOfAProbeType")
    void compilingAConfigurationDoesNotInitializeItsFieldTypes(
            String name, String type, String mark, String annotation) {
        try {
            compile(JavaFileObjects.forSourceLines(
                    "demo.AppConfig",
                    "package demo;",
                    "import io.tiko.annotations.*;",
                    "@Configuration(prefix = \"app\")",
                    "public record AppConfig(" + annotation + type + " probe) {}"));

            assertThat(System.getProperty(mark))
                    .as("the static initializer of " + type + " ran in the compiler")
                    .isNull();
        } finally {
            System.clearProperty(mark);
        }
    }
}
