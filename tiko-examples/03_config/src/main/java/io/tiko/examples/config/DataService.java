package io.tiko.examples.config;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

@Component(scope = Scope.SINGLETON)
public class DataService {
    private final DbConfig db;
    private final AppConfig app;

    @Inject
    public DataService(DbConfig db, AppConfig app) {
        this.db = db;
        this.app = app;
    }

    public String describe() {
        return app.name() + " connecting to " + db.url()
            + " (max=" + db.maxConnections()
            + ", timeout=" + db.connectTimeout().orElse(java.time.Duration.ZERO)
            + ", logLevel=" + app.logLevel() + ")";
    }
}
