package io.tiko.examples.kotlin.outbox.orders

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.Configuration
import io.tiko.annotations.Produces
import io.tiko.examples.kotlin.outbox.Database
import io.tiko.examples.kotlin.outbox.OutboxRelay
import javax.sql.DataSource

@Configuration(prefix = "outbox")
@JvmRecord
data class Settings(
    val dbUrl: String,
    val kafkaBootstrap: String,
    val httpHost: String,
    val httpPort: Int,
)

@Component(scope = Scope.SINGLETON)
class Resources {
    /** The database, with its schema: everything that takes it, the relay included, sees the tables. */
    @Produces(scope = Scope.SINGLETON)
    fun database(settings: Settings): DataSource = Database.open(settings.dbUrl).also(OrderStore::createSchema)

    @Produces(scope = Scope.SINGLETON)
    fun relay(database: DataSource, settings: Settings): OutboxRelay = OutboxRelay(database, settings.kafkaBootstrap)
}
