package io.tiko.examples.kotlin

import io.tiko.test.TestComponent

/** Overrides the production [Label] in `@TikoTest` containers. */
@TestComponent
class TestLabel : Label() {
    override fun text(): String = "test-audit"
}
