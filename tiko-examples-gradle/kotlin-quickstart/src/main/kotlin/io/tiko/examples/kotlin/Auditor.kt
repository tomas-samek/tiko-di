package io.tiko.examples.kotlin

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.Inject
import io.tiko.annotations.Named

/**
 * An `internal` component. Tiko injects the primary constructor with ALL parameters; the
 * default value on [label] is a Kotlin convenience for hand construction, never an
 * "optional dependency". `@param:Named` qualifies constructor parameters, and the nullable
 * `nickname` is satisfied by the nullable `@Produces` in [Settings].
 */
@Component(scope = Scope.SINGLETON)
internal class Auditor
    @Inject
    constructor(
        private val log: EventLog,
        @param:Named("greeting") private val greeting: String,
        @param:Named("nickname") private val nickname: String?,
        private val label: Label = Label(),
    ) {
        fun audit(event: Any) {
            log.record(event.javaClass.simpleName)
            System.getLogger("io.tiko.examples.kotlin")
                .log(
                    System.Logger.Level.INFO,
                    "{0} {1}: {2} ({3})",
                    greeting,
                    nickname ?: "-",
                    event,
                    label.text(),
                )
        }
    }
