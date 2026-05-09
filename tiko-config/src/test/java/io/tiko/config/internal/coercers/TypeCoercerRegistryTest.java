// tiko-config/src/test/java/io/tiko/config/internal/coercers/TypeCoercerRegistryTest.java
package io.tiko.config.internal.coercers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TypeCoercerRegistryTest {

    @Test
    void primitive_int_resolves_to_integer_coercer() {
        TypeCoercer<Integer> c = TypeCoercerRegistry.get(int.class);
        assertThat(c.coerce("42")).isEqualTo(42);
    }

    @Test
    void duration_resolves_and_coerces() {
        TypeCoercer<Duration> c = TypeCoercerRegistry.get(Duration.class);
        assertThat(c.coerce("PT5M")).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void unknown_type_throws_with_class_name() {
        assertThatThrownBy(() -> TypeCoercerRegistry.get(java.io.File.class)).hasMessageContaining("File");
    }

    @Test
    void isSupported_reports_true_for_bundled_and_enums() {
        assertThat(TypeCoercerRegistry.isSupported(int.class)).isTrue();
        assertThat(TypeCoercerRegistry.isSupported(java.io.File.class)).isFalse();
        assertThat(TypeCoercerRegistry.isSupported(java.time.DayOfWeek.class)).isTrue();
    }
}
