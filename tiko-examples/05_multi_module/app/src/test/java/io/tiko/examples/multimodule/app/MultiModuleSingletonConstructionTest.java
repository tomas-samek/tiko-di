package io.tiko.examples.multimodule.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.examples.multimodule.moduleb.NotificationService;
import io.tiko.runtime.Tiko;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * #708: a multi-module container does not construct its SINGLETONs when it starts; each is built on first
 * use. The example's components print a line from their constructor, which is what this test reads.
 */
class MultiModuleSingletonConstructionTest {

    private static final String CONSTRUCTED = "[Module B] NotificationService constructed";

    @Test
    void singletonIsConstructedOnFirstUseNotWhenTheContainerStarts() {
        var original = System.out;
        var printed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try (Container container = Tiko.create()) {
            var printedByStart = printed.toString(StandardCharsets.UTF_8);

            container.get(NotificationService.class);

            assertThat(printedByStart)
                    .as("Tiko.create(...) constructs no SINGLETON in a multi-module container")
                    .doesNotContain("constructed", "initialized");
            assertThat(printed.toString(StandardCharsets.UTF_8))
                    .as("the first lookup constructs it")
                    .contains(CONSTRUCTED);
        } finally {
            System.setOut(original);
        }
    }
}
