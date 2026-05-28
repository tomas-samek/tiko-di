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

    @Test
    void listReturnsEmptyWhenNothingRegistered() {
        try (AggregatingContainer c = newContainer()) {
            Picker<CharSequence> picker = new ContainerPicker<>(c, CharSequence.class);
            assertThat(picker.list()).isEmpty();
        }
    }
}
