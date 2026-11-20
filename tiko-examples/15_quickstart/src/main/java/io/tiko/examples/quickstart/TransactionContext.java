package io.tiko.examples.quickstart;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The unit of work's database transaction, over the unit's {@link Connection}
 * ({@link DataSourceFactory#connection}). {@link RequestTransactions} commits it when a request
 * succeeds and rolls it back when it fails.
 *
 * <p>{@link #close()} runs when the unit ends: if neither commit nor rollback ran, it rolls back,
 * the safety net for code that forgot to end the transaction. The container then closes the
 * connection, which returns it to the pool: this component takes the connection, so it is
 * destroyed first.
 */
@Component(scope = Scope.EVENT)
public class TransactionContext implements AutoCloseable {

    private final Connection connection;
    private boolean ended;

    @Inject
    public TransactionContext(Connection connection) {
        this.connection = connection;
    }

    public void commit() throws SQLException {
        connection.commit();
        ended = true;
    }

    public void rollback() throws SQLException {
        connection.rollback();
        ended = true;
    }

    @Override
    public void close() throws SQLException {
        if (!ended) {
            connection.rollback();
        }
    }
}
