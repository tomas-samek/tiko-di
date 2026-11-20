package com.example

import io.tiko.runtime.Tiko

/** Boots the container, asks it for the [Greeter], and prints a greeting. Run: `./gradlew run`. */
fun main() {
    Tiko.create().use { container ->
        println(container.get(Greeter::class.java).greet("world"))
    }
}
