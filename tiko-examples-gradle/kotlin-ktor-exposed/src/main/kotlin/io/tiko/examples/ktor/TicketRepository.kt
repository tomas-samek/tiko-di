package io.tiko.examples.ktor

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.PostConstruct
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

interface TicketRepository {
    fun save(ticket: Ticket)

    fun find(id: String): Ticket?

    fun all(): List<Ticket>
}

/** The Exposed table: a plain Kotlin `object` owned by Exposed, not a Tiko component. */
object TicketTable : Table("tickets") {
    val id = varchar("id", 36)
    val title = varchar("title", 200)
    val reporter = varchar("reporter", 100)
    override val primaryKey = PrimaryKey(id)
}

/** Each call runs in its own Exposed `transaction` on the produced [Database]. */
@Component(scope = Scope.SINGLETON)
class ExposedTicketRepository(private val db: Database) : TicketRepository {

    @PostConstruct
    fun createSchema() {
        transaction(db) { SchemaUtils.create(TicketTable) }
    }

    override fun save(ticket: Ticket) {
        transaction(db) {
            TicketTable.insert {
                it[id] = ticket.id
                it[title] = ticket.title
                it[reporter] = ticket.reporter
            }
        }
    }

    override fun find(id: String): Ticket? =
        transaction(db) {
            TicketTable.selectAll().where { TicketTable.id eq id }.singleOrNull()?.toTicket()
        }

    override fun all(): List<Ticket> =
        transaction(db) { TicketTable.selectAll().orderBy(TicketTable.title).map { it.toTicket() } }

    private fun ResultRow.toTicket() =
        Ticket(this[TicketTable.id], this[TicketTable.title], this[TicketTable.reporter])
}
