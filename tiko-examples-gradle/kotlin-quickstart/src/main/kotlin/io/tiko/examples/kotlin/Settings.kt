package io.tiko.examples.kotlin

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.Produces

/** Host of two `@Produces` methods: a named non-null value and a named nullable one. */
@Component(scope = Scope.SINGLETON)
class Settings {

    @Produces(scope = Scope.SINGLETON, name = "greeting")
    fun greeting(): String = "Order"

    // Nullable return: injectable only into `String?` points (exact nullability match).
    @Produces(scope = Scope.SINGLETON, name = "nickname")
    fun nickname(): String? = null
}
