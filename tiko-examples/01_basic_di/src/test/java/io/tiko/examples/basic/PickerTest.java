package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage for {@code Picker<T>} runtime lookup primitive.
 */
class PickerTest {

    @Test
    void list_returns_all_greeter_impls() {
        try (Container container = Tiko.create()) {
            PickerConsumer consumer = container.get(PickerConsumer.class);
            List<Greeter> all = consumer.all();
            assertThat(all).hasSize(2).extracting(Greeter::greet).containsExactlyInAnyOrder("Hello", "Hola");
        }
    }

    @Test
    void byName_resolves_named_components() {
        try (Container container = Tiko.create()) {
            PickerConsumer consumer = container.get(PickerConsumer.class);
            assertThat(consumer.byLanguageName("english")).map(Greeter::greet).hasValue("Hello");
            assertThat(consumer.byLanguageName("spanish")).map(Greeter::greet).hasValue("Hola");
        }
    }

    @Test
    void byName_returns_empty_for_unknown_name() {
        try (Container container = Tiko.create()) {
            PickerConsumer consumer = container.get(PickerConsumer.class);
            Optional<Greeter> french = consumer.byLanguageName("french");
            assertThat(french).isEmpty();
        }
    }

    @Test
    void byImplClass_resolves_specific_implementation() {
        try (Container container = Tiko.create()) {
            PickerConsumer consumer = container.get(PickerConsumer.class);
            Optional<EnglishGreeter> english = consumer.byImplClass(EnglishGreeter.class);
            assertThat(english).isPresent().get().isInstanceOf(EnglishGreeter.class);
            assertThat(english.get().greet()).isEqualTo("Hello");
        }
    }

    @Test
    void byImplClass_returns_same_singleton_as_named_lookup() {
        try (Container container = Tiko.create()) {
            PickerConsumer consumer = container.get(PickerConsumer.class);
            Greeter viaPicker = consumer.byImplClass(EnglishGreeter.class).orElseThrow();
            Greeter viaContainer = container.get(Greeter.class, "english");
            assertThat(viaPicker).isSameAs(viaContainer);
        }
    }

    @Test
    void picker_is_typed_at_the_boundary() {
        try (Container container = Tiko.create()) {
            // Compile-time guarantee: Picker<Greeter>.byImplClass refuses non-Greeter types.
            // This verification is structural — see PickerConsumer.byImplClass signature
            // <T extends Greeter>; the test below is the runtime echo of that constraint.
            PickerConsumer consumer = container.get(PickerConsumer.class);
            assertThat(consumer.all()).allMatch(Greeter.class::isInstance);
        }
    }
}
