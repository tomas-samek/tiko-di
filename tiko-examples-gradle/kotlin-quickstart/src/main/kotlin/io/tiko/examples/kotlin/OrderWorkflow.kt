package io.tiko.examples.kotlin

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.EventHandler
import io.tiko.annotations.EventTrigger
import io.tiko.annotations.Inject

/** Constructor injection between Kotlin classes plus an `@EventTrigger` chain. */
@Component(scope = Scope.SINGLETON)
internal class OrderWorkflow
    @Inject
    constructor(private val auditor: Auditor) {

        @EventHandler
        @EventTrigger(eventName = "OrderValidated")
        fun onPlaced(event: OrderPlaced): OrderValidated {
            auditor.audit(event)
            return OrderValidated(event.orderId)
        }

        @EventHandler
        fun onValidated(event: OrderValidated) {
            auditor.audit(event)
        }
    }
