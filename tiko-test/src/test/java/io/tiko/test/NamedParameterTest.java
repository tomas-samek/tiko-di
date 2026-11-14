package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.annotations.Named;
import io.tiko.test.fixtures.Greeter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #715: {@code @Named} on a test-method or lifecycle-method parameter picks the named component.
 * The extension reads the annotation at run time, so {@code @Named} is retained in the class file.
 */
@TikoTest
class NamedParameterTest {

    private String greetingSeenBeforeEach;

    @BeforeEach
    void rememberTheFormalGreeting(@Named("formal") Greeter greeter) {
        greetingSeenBeforeEach = greeter.greet();
    }

    @Test
    void namedTestParameterResolvesTheNamedComponent(@Named("casual") Greeter greeter) {
        assertThat(greeter.greet()).isEqualTo("Hi");
    }

    @Test
    void namedLifecycleParameterResolvesTheNamedComponent() {
        assertThat(greetingSeenBeforeEach).isEqualTo("Good day");
    }
}
