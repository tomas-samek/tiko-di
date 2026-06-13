package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.PickBuilder;
import io.tiko.annotations.Pick;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

/**
 * #350: the fluent builder ({@link io.tiko.PickBuilder}, returned by
 * {@code container.pick()}) and the qualifier annotation ({@link io.tiko.annotations.Pick})
 * now have distinct simple names, so a single source file can import and use both without
 * a fully-qualified-name workaround. This file importing both simple names IS the proof —
 * before the rename, two {@code Pick} imports could not coexist.
 */
class PickNamingCoexistenceTest {

    @Test
    void builderAndAnnotationCoexistWithoutFullyQualifiedNames() {
        // Annotation reachable by its simple name in this file...
        assertThat(Pick.class.isAnnotation()).isTrue();

        // ...alongside the builder, also by simple name.
        try (Container container = Tiko.create()) {
            PickBuilder<Greeter> builder = container.pick(Greeter.class).withName("english");
            assertThat(builder.resolve()).isInstanceOf(EnglishGreeter.class);
        }
    }
}
