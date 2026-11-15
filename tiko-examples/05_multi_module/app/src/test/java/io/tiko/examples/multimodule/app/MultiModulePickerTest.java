package io.tiko.examples.multimodule.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.examples.multimodule.modulea.Channel;
import io.tiko.examples.multimodule.modulea.ChannelCatalog;
import io.tiko.examples.multimodule.moduleb.ChannelReport;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

/**
 * #732: an injected {@code Picker<T>} lists what {@code container.getAll(T)} lists on the
 * application's container, whichever module the component holding it is in. Each module here has
 * one {@link Channel} and one component with a {@code Picker<Channel>}.
 */
class MultiModulePickerTest {

    @Test
    void injectedPickerListsTheImplementationsOfEveryModule() {
        try (Container container = Tiko.create()) {
            var all =
                    container.getAll(Channel.class).stream().map(Channel::name).toList();

            assertThat(all).containsExactlyInAnyOrder("in-app", "email");
            assertThat(container.get(ChannelCatalog.class).names())
                    .as("the picker of a component in module A")
                    .containsExactlyElementsOf(all);
            assertThat(container.get(ChannelReport.class).names())
                    .as("the picker of a component in module B")
                    .containsExactlyElementsOf(all);
        }
    }
}
