package io.tiko.examples.kotlin

import io.tiko.Scope
import io.tiko.annotations.Component
import java.util.concurrent.CopyOnWriteArrayList

/** Records the names of the events the handlers saw, in arrival order. */
@Component(scope = Scope.SINGLETON)
class EventLog {
    private val seen = CopyOnWriteArrayList<String>()

    fun record(name: String) {
        seen.add(name)
    }

    fun chain(): List<String> = seen.toList()
}
