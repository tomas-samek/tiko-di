package io.tiko.config.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NearestKeyTest {

    @Test
    void suggests_the_closest_candidate_within_the_edit_threshold() {
        // kebab-case near-miss for poolSize (the benchmark's real config failure), edit distance 2.
        assertThat(NearestKey.suggest("pool-size", Set.of("url", "poolSize"))).isEqualTo("poolSize");
    }

    @Test
    void returns_null_when_nothing_is_close_enough() {
        assertThat(NearestKey.suggest("completelyUnrelated", Set.of("url"))).isNull();
    }

    @Test
    void returns_null_for_no_candidates() {
        assertThat(NearestKey.suggest("anything", Set.of())).isNull();
    }

    static java.util.stream.Stream<Arguments> hints() {
        return java.util.stream.Stream.of(
                Arguments.of("close match yields a hint", "pool-size", Set.of("poolSize"), " Did you mean 'poolSize'?"),
                Arguments.of("far match yields no hint", "zzz", Set.of("poolSize"), ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hints")
    void hint_renders_only_for_close_matches(String name, String input, Set<String> candidates, String expected) {
        assertThat(NearestKey.hint(input, candidates, UnaryOperator.identity())).isEqualTo(expected);
    }

    @Test
    void hint_applies_the_display_mapping_to_the_suggestion() {
        assertThat(NearestKey.hint("pool-size", Set.of("poolSize"), s -> "db." + s))
                .isEqualTo(" Did you mean 'db.poolSize'?");
    }
}
