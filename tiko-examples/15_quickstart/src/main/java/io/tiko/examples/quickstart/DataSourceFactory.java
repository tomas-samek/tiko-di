package io.tiko.examples.quickstart;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Produces;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Canonical HikariCP recipe — plug in the connection pool via {@code @Produces}.
 * The returned {@link HikariDataSource} is {@link AutoCloseable}, so the container
 * drains the pool at shutdown without an explicit {@code @PreDestroy} here.
 *
 * <p>The producer creates the schema before it returns the pool, so everything that
 * takes the {@code DataSource}, the HTTP server included, sees it, and a failed
 * migration fails boot. With Flyway, the same place calls
 * {@code Flyway.configure().dataSource(ds).load().migrate()}.
 */
@Component(scope = Scope.SINGLETON)
public class DataSourceFactory {

    private final AppConfig config;

    @Inject
    public DataSourceFactory(AppConfig config) {
        this.config = config;
    }

    @Produces(scope = Scope.SINGLETON)
    public DataSource dataSource() {
        var hc = new HikariConfig();
        hc.setJdbcUrl(config.db().url());
        hc.setUsername(config.db().user());
        hc.setPassword(config.db().password());
        hc.setMaximumPoolSize(config.db().poolSize());
        var ds = new HikariDataSource(hc);
        try {
            createSchema(ds);
            return ds;
        } catch (SQLException | IOException | RuntimeException e) {
            ds.close();
            throw e instanceof RuntimeException failure ? failure : new IllegalStateException("Schema failed", e);
        }
    }

    private static void createSchema(DataSource ds) throws SQLException, IOException {
        String script;
        try (InputStream in = DataSourceFactory.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new UncheckedIOException(new IOException("schema.sql not found on classpath"));
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var c = ds.getConnection();
                var st = c.createStatement()) {
            for (String stmt : script.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) st.execute(trimmed);
            }
        }
    }
}
