@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.tiko.coroutines

import io.tiko.Event
import io.tiko.runtime.CarriedUnit
import io.tiko.runtime.UnitCarrier
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job

/**
 * Keeps one unit of work with one coroutine. Wherever the coroutine runs, its unit is installed on
 * that thread: the same EVENT-scoped instances and the same origin chain before and after every
 * suspension. The thread gets back what it had when the coroutine leaves it.
 *
 * The element never carries a unit that was opened before its coroutine existed. kotlinx hands a
 * new coroutine a copy of every [CopyableThreadContextElement] ([copyForChild]), also of the one
 * passed to `withContext` or `launch`, so the instance that ends up in a coroutine opens the unit
 * itself, the first time the coroutine runs, and closes it when the coroutine's [Job] completes.
 * A coroutine that is cancelled before it ever runs opens nothing.
 *
 * That makes the two cases of the unit-of-work rule one mechanism:
 * - `withContext`, `coroutineScope` and the other scoping functions keep the element, and so the
 *   unit;
 * - `launch` and `async` get a copy, and so a unit of their own, as every asynchronous hop does.
 *
 * `CoroutineContextElementContractTest` pins the kotlinx behaviour this relies on.
 */
internal class UnitOfWorkElement(
    private val carrier: UnitCarrier,
    private val origin: Event<*>?,
) : CopyableThreadContextElement<CarriedUnit.Displaced> {

    companion object Key : CoroutineContext.Key<UnitOfWorkElement>

    override val key: CoroutineContext.Key<UnitOfWorkElement> get() = Key

    /** Written by the coroutine's first run, read by its later ones: a coroutine runs on one thread at a time. */
    @Volatile
    private var unit: CarriedUnit? = null

    override fun updateThreadContext(context: CoroutineContext): CarriedUnit.Displaced =
        (unit ?: open(context)).install()

    override fun restoreThreadContext(context: CoroutineContext, oldState: CarriedUnit.Displaced) {
        unit?.restore(oldState)
    }

    private fun open(context: CoroutineContext): CarriedUnit {
        val job = checkNotNull(context[Job]) {
            "A unit of work needs a coroutine with a Job: the unit closes when the Job completes."
        }
        val opened = carrier.openCarriedUnit(origin)
        unit = opened
        job.invokeOnCompletion { opened.close() }
        return opened
    }

    override fun copyForChild(): CopyableThreadContextElement<CarriedUnit.Displaced> =
        UnitOfWorkElement(carrier, origin)

    /**
     * Reached when a coroutine that has a unit is handed another element of this kind, which only
     * [inEventScope] creates. A unit of work does not nest.
     */
    override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext =
        throw IllegalStateException(UNIT_ALREADY_OPEN)
}

internal const val UNIT_ALREADY_OPEN: String =
    "inEventScope called while a unit of work is already open. " +
        "EVENT is single-frame in 0.x.0; nesting is not supported."
