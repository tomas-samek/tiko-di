package com.example

import io.tiko.Scope
import io.tiko.annotations.Component

/**
 * A component of the starter: replace it with your own. The processor finds it at compile time
 * and wires one instance into the generated container.
 */
@Component(scope = Scope.SINGLETON)
class Greeter {
    fun greet(name: String): String = "Hello, $name!"
}
