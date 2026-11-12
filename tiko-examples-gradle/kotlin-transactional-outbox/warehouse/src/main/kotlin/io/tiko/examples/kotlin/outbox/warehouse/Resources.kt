package io.tiko.examples.kotlin.outbox.warehouse

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
)

@Component(scope = Scope.SINGLETON)
class Resources {
    @Produces(scope = Scope.SINGLETON)
    fun database(settings: Settings): DataSource = Database.open(settings.dbUrl)

    @Produces(scope = Scope.SINGLETON)
    fun relay(database: DataSource, settings: Settings): OutboxRelay = OutboxRelay(database, settings.kafkaBootstrap)
}
