package io.tiko.examples.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.Produces
import javax.sql.DataSource
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Produces the connection pool and the Exposed [Database] on top of it. The pool is
 * `AutoCloseable`, so the container closes it on shutdown.
 */
@Component(scope = Scope.SINGLETON)
class Persistence(private val config: DbConfig) {

    @Produces(scope = Scope.SINGLETON)
    fun dataSource(): DataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.poolSize
            },
        )

    @Produces(scope = Scope.SINGLETON)
    fun database(dataSource: DataSource): Database = Database.connect(dataSource)
}
