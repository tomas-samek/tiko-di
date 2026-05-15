package io.tiko.examples.persistence.infra;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Produces;
import io.tiko.examples.persistence.config.DbConfig;
import javax.sql.DataSource;

/**
 * Produces the application-wide HikariCP-backed {@link DataSource}. The
 * resulting {@code HikariDataSource} is {@link AutoCloseable}, so Tiko
 * drains the pool automatically at container shutdown.
 */
@Component(scope = Scope.SINGLETON)
public class HikariDataSourceFactory {

    private final DbConfig config;

    @Inject
    public HikariDataSourceFactory(DbConfig config) {
        this.config = config;
    }

    @Produces(scope = Scope.SINGLETON)
    public DataSource dataSource() {
        var hc = new HikariConfig();
        hc.setJdbcUrl(config.url());
        hc.setUsername(config.user());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(config.poolSize());
        hc.setAutoCommit(false);
        return new HikariDataSource(hc);
    }
}
