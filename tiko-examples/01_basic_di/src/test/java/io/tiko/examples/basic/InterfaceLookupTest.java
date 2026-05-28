package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.NoSuchComponentException;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

class InterfaceLookupTest {

    @Test
    void get_byInterface_returnsUnnamedDefaultImpl() {
        try (Container container = Tiko.create()) {
            Speaker speaker = container.get(Speaker.class);
            assertThat(speaker).isInstanceOf(DefaultSpeaker.class);
            assertThat(speaker.introduce()).contains("default speaker");
        }
    }

    @Test
    void get_byInterface_alsoReachableByConcreteClass() {
        try (Container container = Tiko.create()) {
            Speaker viaInterface = container.get(Speaker.class);
            DefaultSpeaker viaConcrete = container.get(DefaultSpeaker.class);
            assertThat(viaInterface).isSameAs(viaConcrete);
        }
    }

    @Test
    void get_byInterface_whenOnlyNamedImplsExist_throws() {
        // Greeter has two @Component impls (EnglishGreeter, SpanishGreeter) — both named.
        // No unnamed "default" exists, so get(Greeter.class) without a name must fail.
        try (Container container = Tiko.create()) {
            assertThatThrownBy(() -> container.get(Greeter.class)).isInstanceOf(NoSuchComponentException.class);
        }
    }
}
