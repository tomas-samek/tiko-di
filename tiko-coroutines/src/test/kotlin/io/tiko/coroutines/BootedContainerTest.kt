package io.tiko.coroutines

import io.tiko.Container
import io.tiko.UnitOfWork
import io.tiko.coroutines.fixtures.CloseLog
import io.tiko.coroutines.fixtures.Requests
import io.tiko.events.EventEndingEvent
import io.tiko.events.EventStartedEvent
import io.tiko.runtime.Tiko
import io.tiko.runtime.UnitCarrier
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout

/**
 * Boots the container generated from the Java fixtures of this module for each test, and records
 * the unit lifecycle events it publishes. A test that waits for a coroutine that never comes fails
 * after a minute.
 */
@Timeout(60)
abstract class BootedContainerTest {

    protected lateinit var container: Container
    protected lateinit var requests: Requests
    protected lateinit var unitOfWork: UnitOfWork
    protected lateinit var closeLog: CloseLog

    /** `started <id>` and `ending <id>` for every unit of work, in publishing order. */
    protected val lifecycle = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun boot() {
        container = Tiko.create()
        container.eventBus.subscribe(EventStartedEvent::class.java) { lifecycle += "started ${it.eventId()}" }
        container.eventBus.subscribe(EventEndingEvent::class.java) { lifecycle += "ending ${it.eventId()}" }
        requests = container.get(Requests::class.java)
        unitOfWork = requests.unitOfWork()
        closeLog = container.get(CloseLog::class.java)
    }

    @AfterEach
    fun shutDown() {
        container.close()
    }

    /** Whether a unit of work is open on the calling thread. */
    protected fun unitOpenHere(): Boolean = (container as UnitCarrier).hasUnitOpen()

    protected fun unitsStarted(): Int = lifecycle.count { it.startsWith("started ") }

    /** The ids of the units of work that have published both their started and their ending event. */
    protected fun unitsEnded(): Set<String> {
        val started = lifecycle.filter { it.startsWith("started ") }.map { it.substringAfter(' ') }.toSet()
        return lifecycle.filter { it.startsWith("ending ") }.map { it.substringAfter(' ') }.filter { it in started }.toSet()
    }

    /**
     * Waits for [condition]. A child coroutine's unit closes as part of the child's completion,
     * which whoever joined the child can see a moment before the unit's teardown has run.
     */
    protected suspend fun eventually(what: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) {
                while (!condition()) {
                    delay(5)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Not within 5 s: $what", e)
        }
    }
}
