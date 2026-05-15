package io.tiko.examples.persistence.infra;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Produces;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Produces a REQUEST-scoped {@link Connection}. Each REQUEST scope opens a
 * fresh pool connection with {@code autoCommit=false} and returns it on
 * scope teardown (Tiko's implicit-AutoCloseable handling closes the
 * connection, which Hikari intercepts to return it to the pool).
 *
 * <p>Because {@code java.sql.Connection} is an interface, SINGLETON
 * consumers (like {@code OrderRepository}) can inject {@code Connection}
 * directly — the Tiko annotation processor generates an auto-proxy that
 * resolves to the current scope's connection on every method call.
 */
@Component(scope = Scope.REQUEST)
public class JdbcConnectionProvider {

    private final DataSource ds;

    @Inject
    public JdbcConnectionProvider(DataSource ds) {
        this.ds = ds;
    }

    @Produces(scope = Scope.REQUEST)
    public Connection connection() {
        try {
            var c = ds.getConnection();
            c.setAutoCommit(false);
            return c;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to acquire JDBC connection", e);
        }
    }
}
