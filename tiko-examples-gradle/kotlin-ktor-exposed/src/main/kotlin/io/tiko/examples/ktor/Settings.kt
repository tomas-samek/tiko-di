package io.tiko.examples.ktor

import io.tiko.annotations.Configuration
import io.tiko.annotations.Default

/** The `db` section of `application.yaml`. A `@JvmRecord data class` binds like a Java record. */
@Configuration(prefix = "db")
@JvmRecord
data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
    @Default("4") val poolSize: Int,
)

/** The `http` section of `application.yaml`. */
@Configuration(prefix = "http")
@JvmRecord
data class HttpConfig(@Default("8080") val port: Int)
