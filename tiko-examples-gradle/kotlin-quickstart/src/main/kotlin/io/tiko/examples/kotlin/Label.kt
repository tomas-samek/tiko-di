package io.tiko.examples.kotlin

import io.tiko.annotations.Component

/** Default PROTOTYPE scope; used as the default value of a constructor parameter below. */
@Component
open class Label {
    open fun text(): String = "audit"
}
