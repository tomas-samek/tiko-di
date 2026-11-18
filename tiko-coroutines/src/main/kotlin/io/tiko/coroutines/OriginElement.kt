package io.tiko.coroutines

import io.tiko.Event
import io.tiko.runtime.EventChainContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/**
 * Keeps the event a coroutine's work descends from current wherever the coroutine runs, for a
 * coroutine that has no unit of work: a handler of a framework lifecycle event. A coroutine with a
 * unit gets its origin from the unit ([UnitOfWorkElement]).
 */
internal class OriginElement(private val origin: Event<*>?) :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<Event<*>?> {

    companion object Key : CoroutineContext.Key<OriginElement>

    override fun updateThreadContext(context: CoroutineContext): Event<*>? = EventChainContext.enter(origin)

    override fun restoreThreadContext(context: CoroutineContext, oldState: Event<*>?) {
        EventChainContext.exit(oldState)
    }
}
