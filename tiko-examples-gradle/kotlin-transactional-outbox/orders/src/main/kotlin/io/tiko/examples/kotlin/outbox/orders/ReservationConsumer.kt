package io.tiko.examples.kotlin.outbox.orders

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.EventTrigger
import io.tiko.examples.kotlin.outbox.RESULTS
import io.tiko.examples.kotlin.outbox.ReservationDecided
import io.tiko.kafka.annotations.KafkaSource

@Component(scope = Scope.SINGLETON)
class ReservationConsumer(private val orders: OrderStore) {
    @KafkaSource(topic = RESULTS)
    @EventTrigger(eventName = "ReservationRecorded")
    fun receive(result: ReservationDecided): ReservationDecided? {
        if (orders.record(result)) return result
        log.log(System.Logger.Level.WARNING, "Ignoring result for unknown order or conflicting decision: {0}", result)
        return null
    }

    companion object {
        private val log = System.getLogger(ReservationConsumer::class.java.name)
    }
}
