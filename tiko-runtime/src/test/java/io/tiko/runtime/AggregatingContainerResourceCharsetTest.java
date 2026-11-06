package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The processor writes its resources as UTF-8 (#582), so the runtime must read them as UTF-8 whatever the
 * platform default charset is: the non-ASCII {@code @Configuration} record name in this module's test
 * {@code META-INF/tiko/configs.txt} must still route its config to the owning module.
 */
class AggregatingContainerResourceCharsetTest {

    @Test
    void nonAsciiConfigNameInConfigsTxtSurvivesTheRoundTrip() {
        StubContainer.INJECTED_CONFIG_TYPES.clear();
        try (var c = new AggregatingContainer(new LocalEventBus(), ctx -> {}, null, Duration.ofSeconds(1))) {
            c.injectConfigs(Map.of(ÜberConfig.class, new ÜberConfig("x")));
        }

        var injected = List.copyOf(StubContainer.INJECTED_CONFIG_TYPES);
        assertThat(injected).hasSize(1);
        assertThat(injected.get(0)).isEqualTo(ÜberConfig.class);
    }
}
