package io.tiko.examples.kotlin

import io.tiko.Container
import io.tiko.test.RecordingEventBus
import io.tiko.test.TikoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@TikoTest
class QuickstartTikoTest {

    @Test
    fun eventChainRunsInTheTestContainer(
        container: Container,
        bus: RecordingEventBus,
    ) {
        container.runInEventScope { bus.publish(OrderPlaced("A1")) }

        bus.assertPublished(OrderPlaced::class.java)
        assertThat(container.get(EventLog::class.java).chain())
            .containsExactly("OrderPlaced", "OrderValidated")
    }

    @Test
    fun labelIsTheTestOverride(label: Label) {
        assertThat(label.text()).isEqualTo("test-audit")
    }
}
