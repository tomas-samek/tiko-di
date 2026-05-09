package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamedLookupTest {

    @Test
    void get_byInterfaceAndName_returnsMatchingImplementation() {
        try (Container container = Tiko.create()) {
            Greeter english = container.get(Greeter.class, "english");
            Greeter spanish = container.get(Greeter.class, "spanish");

            assertThat(english).isInstanceOf(EnglishGreeter.class);
            assertThat(spanish).isInstanceOf(SpanishGreeter.class);
            assertThat(english.greet()).isEqualTo("Hello");
            assertThat(spanish.greet()).isEqualTo("Hola");
        }
    }

    @Test
    void get_byConcreteClassAndName_works() {
        try (Container container = Tiko.create()) {
            EnglishGreeter greeter = container.get(EnglishGreeter.class, "english");
            assertThat(greeter.greet()).isEqualTo("Hello");
        }
    }

    @Test
    void get_byNameReturnsSameSingletonInstance() {
        try (Container container = Tiko.create()) {
            Greeter a = container.get(Greeter.class, "english");
            Greeter b = container.get(Greeter.class, "english");
            assertThat(a).isSameAs(b);
        }
    }

    @Test
    void get_unknownName_throws() {
        try (Container container = Tiko.create()) {
            assertThatThrownBy(() -> container.get(Greeter.class, "french"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void get_knownNameButIncompatibleType_throws() {
        try (Container container = Tiko.create()) {
            // "english" exists but as EnglishGreeter, not MessageService
            assertThatThrownBy(() -> container.get(MessageService.class, "english"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
