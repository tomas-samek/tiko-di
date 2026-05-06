// tiko-config/src/test/java/io/tiko/config/internal/coercers/CompositeCoercersTest.java
package io.tiko.config.internal.coercers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeCoercersTest {

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
}
