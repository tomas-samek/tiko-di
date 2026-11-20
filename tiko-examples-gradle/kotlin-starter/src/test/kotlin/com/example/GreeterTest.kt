package com.example

import io.tiko.test.TikoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `@TikoTest` boots a container for the test and hands components in as parameters. */
@TikoTest
class GreeterTest {

    @Test
    fun greetsByName(greeter: Greeter) {
        assertThat(greeter.greet("world")).isEqualTo("Hello, world!")
    }
}
