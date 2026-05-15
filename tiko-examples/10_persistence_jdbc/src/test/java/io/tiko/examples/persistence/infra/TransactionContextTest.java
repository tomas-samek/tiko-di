package io.tiko.examples.persistence.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TransactionContext} semantics directly against H2.
 * Three cases: explicit commit persists; close-without-commit rolls back;
 * explicit rollback discards.
 */
class TransactionContextTest {

    private static final String URL = "jdbc:h2:mem:txctx;DB_CLOSE_DELAY=-1";

    private Connection setupConn;

    @BeforeEach
    void setUp() throws SQLException {
        setupConn = DriverManager.getConnection(URL);
        try (Statement st = setupConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS t");
            st.execute("CREATE TABLE t (id INT PRIMARY KEY)");
            setupConn.commit();
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        setupConn.close();
    }

    @Test
    void commitPersistsRow() throws Exception {
        try (Connection c = DriverManager.getConnection(URL)) {
            c.setAutoCommit(false);
            try (TransactionContext tx = new TransactionContext(c)) {
                try (Statement st = c.createStatement()) {
                    st.execute("INSERT INTO t VALUES (1)");
                }
                tx.commit();
            }
        }
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void closeWithoutCommitRollsBack() throws Exception {
        try (Connection c = DriverManager.getConnection(URL)) {
            c.setAutoCommit(false);
            try (TransactionContext tx = new TransactionContext(c)) {
                try (Statement st = c.createStatement()) {
                    st.execute("INSERT INTO t VALUES (2)");
                }
                // No commit() — close() must roll back.
            }
        }
        assertThat(rowCount()).isEqualTo(0);
    }

    @Test
    void explicitRollbackDiscardsInsert() throws Exception {
        try (Connection c = DriverManager.getConnection(URL)) {
            c.setAutoCommit(false);
            try (TransactionContext tx = new TransactionContext(c)) {
                try (Statement st = c.createStatement()) {
                    st.execute("INSERT INTO t VALUES (3)");
                }
                tx.rollback();
            }
        }
        assertThat(rowCount()).isEqualTo(0);
    }

    private int rowCount() throws SQLException {
        try (Statement st = setupConn.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
