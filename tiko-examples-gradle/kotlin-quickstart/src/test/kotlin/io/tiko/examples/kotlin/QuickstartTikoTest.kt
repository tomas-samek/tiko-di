package io.tiko.examples.kotlin

import io.tiko.Container
import io.tiko.events.EventStartedEvent
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
    fun eachOrderPlacedAtTheDeskRunsInItsOwnUnitOfWork(container: Container) {
        val units = mutableListOf<String>()
        container.eventBus.subscribe(EventStartedEvent::class.java) { units += it.eventId() }
        val desk = container.get(OrderDesk::class.java)

        desk.place("A1")
        desk.place("A2")

        assertThat(units).hasSize(2).doesNotHaveDuplicates()
        assertThat(container.get(EventLog::class.java).chain())
            .containsExactly("OrderPlaced", "OrderValidated", "OrderPlaced", "OrderValidated")
    }

    @Test
    fun labelIsTheTestOverride(label: Label) {
        assertThat(label.text()).isEqualTo("test-audit")
    }
}
