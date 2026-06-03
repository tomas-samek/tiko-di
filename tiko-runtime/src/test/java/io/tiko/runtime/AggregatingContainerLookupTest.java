package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tiko.ContainerInitializationException;
import io.tiko.NoSuchComponentException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed framework exceptions (#98) on {@link AggregatingContainer}'s multi-module dispatch:
 * a lookup miss surfaces as {@link NoSuchComponentException} (with structured fields), and discovery
 * / config-ownership failures surface as {@link ContainerInitializationException}. Backed by the
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

    @Test
    void injectConfigsForUnownedTypeThrowsContainerInitialization() {
        try (AggregatingContainer c = newContainer()) {
            assertThatThrownBy(() -> c.injectConfigs(Map.of(String.class, "x")))
                    .isInstanceOf(ContainerInitializationException.class)
                    .hasMessageContaining("No module owns config type");
        }
    }
}
