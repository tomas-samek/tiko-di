package io.tiko.examples.kafka

/**
 * A record on the `payments` topic. Kafka payloads under the default JSON serializer are
 * `@JvmRecord data class`es: a plain `data class` here is a compile error.
 */
@JvmRecord
data class PaymentReceived(val paymentId: String, val orderId: String, val amountCents: Long)

/** What the ledger made of a payment. [firstDelivery] is false for a redelivered payment. */
data class PaymentRecorded(val payment: PaymentReceived, val firstDelivery: Boolean)

/** A record on the `receipts` topic, keyed by [orderId]. */
@JvmRecord
data class Receipt(val paymentId: String, val orderId: String, val amountCents: Long)
