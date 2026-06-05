package io.tiko.examples.quickstart;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Produces;
import javax.sql.DataSource;

/**
 * Canonical HikariCP recipe — plug in the connection pool via {@code @Produces}.
 * The returned {@link HikariDataSource} is {@link AutoCloseable}, so the container
 * drains the pool at shutdown without an explicit {@code @PreDestroy} here.
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
        return new HikariDataSource(hc);
    }
}
