package io.tiko.config.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.tiko.config.ConfigValidationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * YAML aliases and repeated keys in a configuration file fail as located configuration errors,
 * not as a {@code StackOverflowError}, unbounded expansion or a silently dropped value (#498).
 */
class YamlAliasBoundsTest {

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("alias inside its own sequence", "a: &x [*x]\n", "app.yaml:1:", "recursive alias"),
                Arguments.of("alias inside its own mapping", "a: &x {b: *x}\n", "app.yaml:1:", "recursive alias"),
                Arguments.of("exponential alias expansion", aliasBomb(18), "app.yaml", "expands to more than"),
                Arguments.of("repeated top-level key", "a: first\na: second\n", "app.yaml:2:", "duplicate key 'a'"),
                Arguments.of(
                        "repeated nested key", "db:\n  url: x\n  url: y\n", "app.yaml:3:", "duplicate key 'db.url'"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void failsAsALocatedConfigurationError(String name, String yaml, String location, String problem) {
        var failure = catchThrowable(() -> load(yaml));

        assertThat(failure).isInstanceOf(ConfigValidationException.class);
        assertThat(failure.getMessage()).contains(location, problem);
    }

    @Test
    void reusingAnAnchorStillWorks() {
        var data = load("base: &b {x: 1, y: [a, b]}\nother: *b\n").data();

        assertThat(data).containsEntry("other", Map.of("x", "1", "y", List.of("a", "b")));
        assertThat(data).containsEntry("base", data.get("other"));
    }

    /** {@code levels} anchors, each a list holding the previous one twice: 2^levels leaves. */
    private static String aliasBomb(int levels) {
        var yaml = new StringBuilder("l0: &l0 [x, x]\n");
        for (int i = 1; i < levels; i++) {
            yaml.append("l").append(i).append(": &l").append(i);
            yaml.append(" [*l").append(i - 1).append(", *l").append(i - 1).append("]\n");
        }
        return yaml.toString();
    }

    private static YamlLoader.LoadedYaml load(String yaml) {
        return YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "app.yaml");
    }
}
