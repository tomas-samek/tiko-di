package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

/**
 * End-to-end regression for #242: a component that injects {@code @Named("english") Greeter}
 * builds and resolves to the qualified impl at runtime. This injection-site path was previously
 * unexercised — {@code @Named} was only tested through the {@code container.get(type, name)} API —
 * which let a generator bug (a non-compiling factory) ship undetected.
 */
class NamedInjectionIntegrationTest {

    @Test
    void namedDependencyResolvesToTheQualifiedImpl() {
        try (Container container = Tiko.create()) {
            NamedGreeterConsumer consumer = container.get(NamedGreeterConsumer.class);
            assertThat(consumer.greeting()).isEqualTo("Hello"); // EnglishGreeter, via @Named("english")
        }
    }
}
