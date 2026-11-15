package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Picker;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ContainerPicker}'s not-found handling after the exceptions were typed (#98): a miss
 * from the backing container ({@code NoSuchComponentException}) is converted to {@code Optional.empty()}
 * rather than propagating. Backed by the component-less {@link StubContainer}-driven
 * {@link AggregatingContainer}, so every lookup misses.
 */
class ContainerPickerTest {

    private static AggregatingContainer newContainer() {
        return new AggregatingContainer(new LocalEventBus(), ctx -> {}, null, Duration.ofSeconds(1));
    }

    @Test
    void byImplClassReturnsEmptyWhenImplNotRegistered() {
        try (AggregatingContainer c = newContainer()) {
            Picker<CharSequence> picker = new ContainerPicker<>(c, CharSequence.class);
            assertThat(picker.byImplClass(String.class)).isEmpty();
        }
    }

    /** A container whose one component is {@code name}: a stand-in for one module, or for the application. */
    private static AggregatingContainer containerProviding(String name) {
        return new AggregatingContainer(
                new LocalEventBus(),
                ctx -> {},
                null,
                Duration.ofSeconds(1),
                TikoOptions.builder().override(String.class, () -> name).build());
    }

    /** #732: a picker built by a module's generated code asks the application's container, not the module's. */
    @Test
    void pickerBuiltWithTheApplicationsOptionsAsksTheApplicationContainer() {
        try (AggregatingContainer application = containerProviding("application");
                AggregatingContainer module = containerProviding("module")) {
            Picker<CharSequence> picker = new ContainerPicker<>(module, application.options(), CharSequence.class);

            assertThat(picker.byImplClass(String.class)).contains("application");
        }
    }

    /** A single-module container is the application: its options name no other container. */
    @Test
    void pickerOfAStandaloneContainerAsksThatContainer() {
        try (AggregatingContainer module = containerProviding("module")) {
            var standalone = TikoOptions.builder().build();
            Picker<CharSequence> withOptions = new ContainerPicker<>(module, standalone, CharSequence.class);
            Picker<CharSequence> withoutOptions = new ContainerPicker<>(module, null, CharSequence.class);

            assertThat(withOptions.byImplClass(String.class)).contains("module");
            assertThat(withoutOptions.byImplClass(String.class)).contains("module");
        }
    }

    @Test
    void listReturnsEmptyWhenNothingRegistered() {
        try (AggregatingContainer c = newContainer()) {
            Picker<CharSequence> picker = new ContainerPicker<>(c, CharSequence.class);
            assertThat(picker.list()).isEmpty();
        }
    }
}
