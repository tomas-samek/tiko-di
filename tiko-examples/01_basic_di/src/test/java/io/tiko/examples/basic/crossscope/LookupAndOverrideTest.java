package io.tiko.examples.basic.crossscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.examples.basic.EventContext;
import io.tiko.examples.basic.EventContextImpl;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Lookups and overrides at the edges of the generated container: an override reaching a
 * {@code @Produces} parameter (#738), and a proxied EVENT component looked up by its own class (#740).
 */
class LookupAndOverrideTest {

    @Test
    void overrideReachesAProducesParameterAsItReachesAConstructorParameter() {
        UnitSession replacement = () -> 99;
        var options = TikoOptions.builder()
                .override(UnitSession.class, () -> replacement)
                .build();

        try (Container container = Tiko.create(options)) {
            assertThat(container.get(SessionReporter.class).currentSessionNumber())
                    .as("the SINGLETON @Produces method received the override, not the produced session")
                    .isEqualTo(99);
        }
    }

    @Test
    void proxiedEventComponentLookedUpByItsOwnClassIsTheInstanceOfTheCurrentUnit() {
        List<Object> byClass = new ArrayList<>();
        List<Object> allByClass = new ArrayList<>();
        List<Object> byInterface = new ArrayList<>();

        try (Container container = Tiko.create()) {
            container.runInEventScope(() -> {
                byClass.add(container.get(EventContextImpl.class));
                allByClass.addAll(container.getAll(EventContextImpl.class));
                byInterface.add(container.get(EventContext.class));
            });
        }

        assertThat(byClass).singleElement().isInstanceOf(EventContextImpl.class);
        assertThat(allByClass).containsExactlyElementsOf(byClass);
        assertThat(byInterface)
                .as("the interface still resolves to the proxy a longer-lived consumer can keep")
                .singleElement()
                .isNotInstanceOf(EventContextImpl.class);
    }
}
