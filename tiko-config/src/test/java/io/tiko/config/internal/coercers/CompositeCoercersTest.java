// tiko-config/src/test/java/io/tiko/config/internal/coercers/CompositeCoercersTest.java
package io.tiko.config.internal.coercers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.config.CapturingLoggerFinder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompositeCoercersTest {

    @BeforeEach
    void clearCapturedLogs() {
        CapturingLoggerFinder.clear();
    }

    @Test
    void list_coercer_delegates_to_element_coercer() {
        TypeCoercer<List<Integer>> c = CompositeCoercers.list(Coercers.intCoercer());
        assertThat(c.coerce(List.of(1, 2, "3"))).containsExactly(1, 2, 3);
    }

    @Test
    void list_coercer_rejects_non_list_input() {
        TypeCoercer<List<Integer>> c = CompositeCoercers.list(Coercers.intCoercer());
        assertThatThrownBy(() -> c.coerce("not a list")).hasMessageContaining("expected list");
    }

    @Test
    void map_coercer_delegates_to_value_coercer() {
        TypeCoercer<Map<String, Integer>> c = CompositeCoercers.map(Coercers.intCoercer());
        assertThat(c.coerce(Map.of("a", 1, "b", "2"))).containsEntry("a", 1).containsEntry("b", 2);
    }

    @Test
    void optional_coercer_wraps_present_value() {
        TypeCoercer<Optional<Integer>> c = CompositeCoercers.optional(Coercers.intCoercer());
        assertThat(c.coerce(42)).contains(42);
        assertThat(c.coerce(null)).isEmpty();
    }

    @Test
    void set_coercer_delegates_to_element_coercer() {
        TypeCoercer<Set<Integer>> c = CompositeCoercers.set(Coercers.intCoercer());
        assertThat(c.coerce(List.of(1, 2, "3"))).containsExactly(1, 2, 3);
    }

    @Test
    void set_coercer_dedupes_with_order_preservation() {
        TypeCoercer<Set<String>> c = CompositeCoercers.set(Coercers.stringCoercer());
        Set<String> result = c.coerce(List.of("alpha", "beta", "alpha", "gamma", "beta"));
        assertThat(result).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void set_coercer_rejects_non_list_input() {
        TypeCoercer<Set<Integer>> c = CompositeCoercers.set(Coercers.intCoercer());
        assertThatThrownBy(() -> c.coerce("not a list")).hasMessageContaining("expected list");
    }

    @Test
    void set_coercer_handles_empty_list() {
        TypeCoercer<Set<String>> c = CompositeCoercers.set(Coercers.stringCoercer());
        assertThat(c.coerce(List.of())).isEmpty();
    }

    @Test
    void set_coercer_emits_warning_per_duplicate() {
        TypeCoercer<Set<String>> c = CompositeCoercers.set(Coercers.stringCoercer());
        c.coerce(List.of("a", "b", "a", "c", "b"));

        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> r.level() == System.Logger.Level.WARNING)
                .filteredOn(r -> "io.tiko.config".equals(r.loggerName()))
                .extracting(CapturingLoggerFinder.LogEntry::message)
                .containsExactly(
                        "@Configuration Set<X> field: duplicate value 'a' deduped",
                        "@Configuration Set<X> field: duplicate value 'b' deduped");
    }
}
