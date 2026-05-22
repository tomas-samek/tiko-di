package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.EventBus;
import org.junit.jupiter.api.Test;

@TikoTest
class TikoTestExtensionTest {

    @Test
    void containerIsProvidedAsParameter(Container container) {
        assertThat(container).isNotNull();
    }

    @Test
    void eventBusIsTheRecordingSpy(EventBus bus) {
        assertThat(bus).isInstanceOf(RecordingEventBus.class);
    }

    @Test
    void recordingBusCanBeRequestedDirectly(RecordingEventBus bus) {
        assertThat(bus).isNotNull();
    }
}
