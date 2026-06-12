package io.tiko.config.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Scalars must arrive at the binder as their literal text (#343): the record component
 * declares the target type and the coercers parse from text, so YAML 1.1 implicit typing
 * only ever loses information — {@code NO} became {@code Boolean.FALSE} ("Norway
 * problem"), {@code 0644} became octal 420, {@code 1:30} became sexagesimal 90, and the
 * scalar re-parse even stripped quoting, so explicitly quoted {@code "NO"} corrupted
 * too. Plain YAML nulls ({@code ~}, {@code null}, empty) keep their null semantics;
 * quoted "null" is the literal string.
 */
class YamlScalarTypingTest {

    private static Map<String, Object> load(String yaml) {
        return YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yaml")
                .data();
    }

    static Stream<Arguments> literalScalars() {
        return Stream.of(
                Arguments.of("Norway problem, plain", "country: NO", "NO"),
                Arguments.of("Norway problem, quoted", "country: \"NO\"", "NO"),
                Arguments.of("octal-looking file mode", "country: 0644", "0644"),
                Arguments.of("sexagesimal-looking time", "country: 1:30", "1:30"),
                Arguments.of("yes stays text", "country: yes", "yes"),
                Arguments.of("number stays text for the coercer", "country: 8080", "8080"),
                Arguments.of("quoted null is the literal string", "country: \"null\"", "null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("literalScalars")
    void scalarArrivesAsLiteralText(String name, String yaml, String expected) {
        assertThat(load(yaml)).containsEntry("country", expected);
    }

    static Stream<Arguments> plainNulls() {
        return Stream.of(
                Arguments.of("tilde", "country: ~"),
                Arguments.of("null keyword", "country: null"),
                Arguments.of("empty value", "country:"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("plainNulls")
    void plainNullKeepsNullSemantics(String name, String yaml) {
        Map<String, Object> data = load(yaml);
        assertThat(data).containsKey("country");
        assertThat(data.get("country")).isNull();
    }
}
