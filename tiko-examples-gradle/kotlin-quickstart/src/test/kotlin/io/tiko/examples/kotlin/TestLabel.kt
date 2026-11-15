package io.tiko.examples.kotlin

import io.tiko.Scope
import io.tiko.test.TestComponent

/**
 * Overrides the production [Label] in `@TikoTest` containers. A test component has the scope of
 * the component it replaces: [Label] uses the default PROTOTYPE, so this one states it.
 */
@TestComponent(scope = Scope.PROTOTYPE)
class TestLabel : Label() {
    override fun text(): String = "test-audit"
}
