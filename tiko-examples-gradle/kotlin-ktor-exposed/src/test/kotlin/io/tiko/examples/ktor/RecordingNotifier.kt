package io.tiko.examples.ktor

import io.tiko.Scope
import io.tiko.test.TestComponent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Replaces [LogNotifier] wherever a [Notifier] is injected in `@TikoTest` containers. The
 * interface is named in `@TestComponent(Notifier::class)`, so nothing needs to be `open`.
 */
@TestComponent(Notifier::class, scope = Scope.SINGLETON)
class RecordingNotifier : Notifier {
    val sent = CopyOnWriteArrayList<String>()

    override fun send(message: String) {
        sent.add(message)
    }
}
