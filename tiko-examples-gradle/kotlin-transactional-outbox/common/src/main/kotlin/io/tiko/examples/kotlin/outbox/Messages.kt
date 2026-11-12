package io.tiko.examples.kotlin.outbox

import java.util.UUID

const val ORDERS = "kotlin-outbox-orders"
const val RESULTS = "kotlin-outbox-results"

@JvmRecord
data class OrderPlaced(val orderId: UUID, val sku: String, val quantity: Int) {
    init {
        require(SKU.matches(sku) && quantity > 0) { "A SKU and a positive quantity are required" }
    }

    companion object {
        private val SKU = Regex("[a-zA-Z0-9_-]{1,40}")
    }
}

enum class Decision { RESERVED, REJECTED }

@JvmRecord
data class ReservationDecided(val orderId: UUID, val decision: Decision)
