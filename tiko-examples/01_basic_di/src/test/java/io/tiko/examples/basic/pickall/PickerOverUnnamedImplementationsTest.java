package io.tiko.examples.basic.pickall;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

/** #812: a picker lists several unnamed implementations of its type. */
class PickerOverUnnamedImplementationsTest {

    @Test
    void pickerListsEveryUnnamedImplementation() {
        try (Container container = Tiko.create()) {
            assertThat(container.get(ExporterCatalog.class).formats()).containsExactlyInAnyOrder("csv", "json");
        }
    }
}
