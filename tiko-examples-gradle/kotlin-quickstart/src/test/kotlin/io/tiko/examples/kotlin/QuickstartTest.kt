package io.tiko.examples.kotlin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuickstartTest {

    @Test
    fun containerBootsAndEventChainCompletes() {
        val chain = runQuickstart()

        assertThat(chain).containsExactly("OrderPlaced", "OrderValidated")
    }
}
