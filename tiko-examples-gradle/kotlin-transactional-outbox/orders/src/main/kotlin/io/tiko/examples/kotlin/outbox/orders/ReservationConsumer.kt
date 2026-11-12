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
    fun receive(result: ReservationDecided): ReservationDecided {
        orders.record(result)
        return result
    }
}
