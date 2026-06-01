package io.tiko.examples.persistence.infra;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * EVENT-scoped transaction boundary owner. Wraps the same
 * EVENT-scoped {@link Connection} (no proxy needed — same scope) and
 * exposes {@link #commit()} and {@link #rollback()}.
 *
 * <p>Implements {@link AutoCloseable}: at EVENT scope (unit of work) teardown, Tiko's
 * implicit-AutoCloseable handling invokes {@link #close()}. If neither
 * {@code commit()} nor {@code rollback()} ran, {@code close()} rolls
 * back — the safety net for handler code that forgot to commit. We do
 * <strong>not</strong> call {@code connection.close()} here: Tiko's
 * implicit-AutoCloseable handling on the {@code @Produces} Connection
 * returns it to the Hikari pool (reverse-creation order:
 * {@code TransactionContext} depends on {@code Connection}, so this
 * tears down first, then Tiko closes the connection).
 *
 * <p>The intended commit path is {@code TransactionalScope.run(...)}.
 */
@Component(scope = Scope.EVENT)
public class TransactionContext implements AutoCloseable {

    private final Connection connection;
    private boolean committed = false;
    private boolean rolledBack = false;

    @Inject
    public TransactionContext(Connection connection) {
        this.connection = connection;
    }

    public void commit() throws SQLException {
        connection.commit();
        committed = true;
    }

    public void rollback() throws SQLException {
        connection.rollback();
        rolledBack = true;
    }

    @Override
    public void close() throws SQLException {
        if (!committed && !rolledBack) {
            connection.rollback();
        }
    }
}
