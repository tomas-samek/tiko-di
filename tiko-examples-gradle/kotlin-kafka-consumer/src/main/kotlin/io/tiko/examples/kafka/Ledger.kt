package io.tiko.examples.kafka

import io.tiko.Scope
import io.tiko.annotations.Component
import java.util.concurrent.ConcurrentHashMap

/** The payments seen so far. In a real service this is a table with a unique key. */
interface Ledger {
    /** Stores [payment]; returns false when its `paymentId` was already recorded. */
    fun record(payment: PaymentReceived): Boolean

    fun totalCents(orderId: String): Long
}

@Component(scope = Scope.SINGLETON)
class InMemoryLedger : Ledger {
    private val payments = ConcurrentHashMap<String, PaymentReceived>()

    override fun record(payment: PaymentReceived): Boolean = payments.putIfAbsent(payment.paymentId, payment) == null

    override fun totalCents(orderId: String): Long =
        payments.values.filter { it.orderId == orderId }.sumOf { it.amountCents }
}
