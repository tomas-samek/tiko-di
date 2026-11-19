package io.tiko.coroutines

import io.tiko.Container
import kotlinx.coroutines.runBlocking

/**
 * Runs `suspend` bridges (#681). For generated transport-bootstrap code, which calls [run] once for
 * every record a `suspend` `@KafkaSource` bridge takes in; applications don't call it.
 */
public object SuspendBridges {

    /**
     * Runs [bridge] on the calling thread, which waits until it has finished.
     *
     * - The bridge runs in a unit of work of its own, across its suspension points and whatever
     *   dispatcher it switches to. The unit closes before this function returns or throws.
     * - What the bridge returns is published on [container]'s event bus inside that unit, on the
     *   calling thread, so synchronous handlers of the event see the unit's instances. `null` is
     *   not published.
     * - What the bridge throws is thrown to the caller.
     * - An interrupt of the calling thread cancels the bridge. The bridge's `finally` blocks run,
     *   its unit closes, and the interrupt is thrown as an `InterruptedException`.
     *
     * @param bridge the call of the bridge method; generated Java passes a lambda that takes the
     *   continuation, which is what a `suspend () -> Any?` is on the JVM
     * @throws IllegalStateException if a unit of work is already open on the calling thread
     */
    @JvmStatic
    public fun run(container: Container, bridge: suspend () -> Any?) {
        runBlocking {
            container.inEventScope {
                val event = bridge()
                if (event != null) {
                    container.eventBus.publish(event)
                }
            }
        }
    }
}
