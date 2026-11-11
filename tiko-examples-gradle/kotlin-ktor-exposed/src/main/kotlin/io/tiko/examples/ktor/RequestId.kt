package io.tiko.examples.ktor

import io.tiko.Scope
import io.tiko.annotations.Component
import java.util.UUID

/** One id per HTTP request. */
interface RequestId {
    val value: String
}

/**
 * EVENT scope: a fresh instance per unit of work, and [unitOfWork] opens one per request.
 * [TicketService] is a SINGLETON, so it gets a generated proxy that resolves the current
 * request's instance on every call.
 */
@Component(scope = Scope.EVENT)
class RandomRequestId : RequestId {
    override val value: String = UUID.randomUUID().toString()
}
