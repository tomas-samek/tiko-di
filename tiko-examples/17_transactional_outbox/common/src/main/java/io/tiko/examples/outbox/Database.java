package io.tiko.examples.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

public final class Database {
    private Database() {}

    public static DataSource open(String url) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("sa");
        return dataSource;
    }

    public static void initialize(DataSource dataSource, String... statements) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    public static <T> T transaction(DataSource dataSource, Work<T> work) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }
}
