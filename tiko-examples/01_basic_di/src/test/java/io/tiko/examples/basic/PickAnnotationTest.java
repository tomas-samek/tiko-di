package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage for the {@code @Pick(Class)} injection-site annotation.
 * Distinct from {@link PickTest}, which exercises the {@code container.pick(...)}
 * fluent runtime API — different feature, similar name.
 */
class PickAnnotationTest {

    @Test
    void pick_selects_specific_greeter_impls_at_injection_site() {
        try (Container container = Tiko.create()) {
            Polyglot polyglot = container.get(Polyglot.class);
            assertThat(polyglot.greetIn("english")).isEqualTo("Hello");
            assertThat(polyglot.greetIn("spanish")).isEqualTo("Hola");
        }
    }

    @Test
    void pick_returns_same_singleton_as_named_lookup() {
        try (Container container = Tiko.create()) {
            Polyglot polyglot = container.get(Polyglot.class);
            Greeter englishViaName = container.get(Greeter.class, "english");
            Greeter spanishViaName = container.get(Greeter.class, "spanish");

            assertThat(polyglot.greetIn("english")).isEqualTo(englishViaName.greet());
            assertThat(polyglot.greetIn("spanish")).isEqualTo(spanishViaName.greet());
        }
    }
}
