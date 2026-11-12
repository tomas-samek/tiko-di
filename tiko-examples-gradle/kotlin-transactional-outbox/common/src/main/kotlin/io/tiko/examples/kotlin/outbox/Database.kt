package io.tiko.examples.kotlin.outbox

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource

object Database {
    fun open(url: String): DataSource = JdbcDataSource().apply {
        setURL(url)
        user = "sa"
    }

    fun initialize(database: DataSource, vararg statements: String) {
        database.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach { statement.execute(it) }
            }
        }
    }

    fun <T> transaction(database: DataSource, work: (Connection) -> T): T =
        database.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = work(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                try {
                    connection.rollback()
                } catch (rollbackFailure: SQLException) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
}
