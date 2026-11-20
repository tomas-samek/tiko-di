package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tiko.NoSuchComponentException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed framework exceptions (#98) on {@link AggregatingContainer}'s multi-module dispatch:
 * a lookup miss surfaces as {@link NoSuchComponentException} (with structured fields), and a config
 * record no module owns reaches every module. Backed by the
 * {@link StubContainer} registered via {@code src/test/resources/META-INF/tiko/container.properties},
 * which registers no components — so every lookup is a miss.
 */
class AggregatingContainerLookupTest {

    private static AggregatingContainer newContainer() {
        return new AggregatingContainer(new LocalEventBus(), ctx -> {}, null, Duration.ofSeconds(1));
    }

    @Test
    void getUnknownTypeThrowsNoSuchComponentCarryingType() {
        try (AggregatingContainer c = newContainer()) {
            NoSuchComponentException ex =
                    catchThrowableOfType(NoSuchComponentException.class, () -> c.get(String.class));
            assertThat(ex).isNotNull();
            assertThat(ex.type()).isEqualTo(String.class);
            assertThat(ex.qualifier()).isNull();
        }
    }

    @Test
    void getProviderUnknownTypeThrowsNoSuchComponent() {
        try (AggregatingContainer c = newContainer()) {
            assertThatThrownBy(() -> c.getProvider(String.class)).isInstanceOf(NoSuchComponentException.class);
        }
    }

    @Test
    void getProviderUnknownNameThrowsNoSuchComponentCarryingQualifier() {
        try (AggregatingContainer c = newContainer()) {
            NoSuchComponentException ex =
                    catchThrowableOfType(NoSuchComponentException.class, () -> c.getProvider(String.class, "missing"));
            assertThat(ex).isNotNull();
            assertThat(ex.type()).isEqualTo(String.class);
            assertThat(ex.qualifier()).isEqualTo("missing");
        }
    }

    /**
     * #848: a record no module lists in its {@code configs.txt}, such as {@code tiko-kafka}'s
     * {@code KafkaConfig}, goes to every module, as a single-module container takes every bound record.
     */
    @Test
    void injectConfigsHandsATypeNoModuleOwnsToEveryModule() {
        StubContainer.INJECTED_CONFIG_TYPES.clear();
        try (AggregatingContainer c = newContainer()) {
            c.injectConfigs(Map.of(String.class, "x"));

            var injected = List.copyOf(StubContainer.INJECTED_CONFIG_TYPES);
            assertThat(injected).containsExactly(String.class);
        }
    }
}
