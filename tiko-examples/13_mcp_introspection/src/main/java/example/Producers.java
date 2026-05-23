package example;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;

@Component(scope = Scope.SINGLETON)
public final class Producers {

    @Produces(scope = Scope.SINGLETON, name = "primary")
    public DbConfig.HikariShim primaryShim(DbConfig cfg) {
        return new DbConfig.HikariShim(cfg.url(), cfg.username());
    }
}
