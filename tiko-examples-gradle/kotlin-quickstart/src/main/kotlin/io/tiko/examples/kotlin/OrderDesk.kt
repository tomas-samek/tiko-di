package io.tiko.examples.kotlin

import io.tiko.EventBus
import io.tiko.Scope
import io.tiko.UnitOfWork
import io.tiko.annotations.Component
import io.tiko.annotations.Inject

/**
 * Takes orders from outside the application. `UnitOfWork` and `EventBus` are built-in
 * dependencies: the desk opens one unit of work per order and never holds the container.
 */
@Component(scope = Scope.SINGLETON)
internal class OrderDesk
    @Inject
    constructor(
        private val unitOfWork: UnitOfWork,
        private val events: EventBus,
    ) {
        fun place(orderId: String) {
            unitOfWork.runInEventScope { events.publish(OrderPlaced(orderId)) }
        }
    }
