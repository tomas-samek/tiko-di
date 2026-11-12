package io.tiko.examples.kotlin.outbox.warehouse

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.EventTrigger
import io.tiko.examples.kotlin.outbox.ORDERS
import io.tiko.examples.kotlin.outbox.OrderPlaced
import io.tiko.examples.kotlin.outbox.ReservationDecided
import io.tiko.kafka.annotations.KafkaSource

@Component(scope = Scope.SINGLETON)
class OrderConsumer(private val warehouse: WarehouseStore) {
    @KafkaSource(topic = ORDERS)
    @EventTrigger(eventName = "ReservationDecided")
    fun receive(order: OrderPlaced): ReservationDecided = warehouse.reserve(order)
}
