package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins the aggregator's fallback delegation for type-only lookups (#335): a type the
 * concrete-class {@code components.txt} map can't see — an interface key, or an
 * interface-keyed {@link TikoOptions} override — must still resolve by delegating to the
 * module containers, exactly as {@code get(Class, String)} already does. Backed by
 * {@link StubContainer}, which resolves {@link StubService} the way a generated
 * container's routable-types dispatch would.
 */
class AggregatingContainerFallbackLookupTest {

    private static AggregatingContainer newContainer(TikoOptions options) {
        return new AggregatingContainer(new LocalEventBus(), ctx -> {}, null, Duration.ofSeconds(1), options);
    }

    @Test
    void getByInterfaceFallsBackToModuleContainers() {
        try (AggregatingContainer c = newContainer(TikoOptions.builder().build())) {
            assertThat(c.get(StubService.class)).isSameAs(StubContainer.STUB_SERVICE);
        }
    }

    @Test
    void getProviderByInterfaceFallsBackToModuleContainers() {
        try (AggregatingContainer c = newContainer(TikoOptions.builder().build())) {
            assertThat(c.getProvider(StubService.class).get()).isSameAs(StubContainer.STUB_SERVICE);
        }
    }

    @Test
    void interfaceKeyedOverrideResolvesThroughTheAggregator() {
        StubService replacement = new StubService() {};
        TikoOptions options = TikoOptions.builder()
                .override(StubService.class, () -> replacement)
                .build();
        try (AggregatingContainer c = newContainer(options)) {
            assertThat(c.get(StubService.class)).isSameAs(replacement);
        }
    }
}
