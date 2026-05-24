package io.tiko.examples.config;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;

public class Main {
    public static void main(String[] args) {
        try (Container container = Tiko.create(ConfigSources.classpath("config.yaml"))) {
            DbConfig db = container.get(DbConfig.class);
            AppConfig app = container.get(AppConfig.class);
            DataService service = container.get(DataService.class);

            System.out.println("=== @Configuration records bound from config.yaml ===");
            System.out.println("DbConfig:  " + db);
            System.out.println("AppConfig: " + app);

            System.out.println();
            System.out.println("=== Feature checkpoints ===");
            System.out.println("@Default applied on db.maxConnections?    "
                    + (db.maxConnections() == 20 ? "no (YAML supplied 20)" : "no -- see yaml"));
            System.out.println("@Default applied on app.logLevel?         "
                    + (app.logLevel().equals("INFO") ? "yes -- falls back to \"INFO\" when LOG_LEVEL is unset" : "no"));
            System.out.println("@Default applied on app.server.port?      "
                    + (app.server().port() == 8080 ? "yes -- falls back to 8080 (omitted in yaml)" : "no"));
            System.out.println("${DB_URL:...} interpolation resolved to:  " + db.url());
            System.out.println("Nested record AppConfig.server bound to:  " + app.server());

            System.out.println();
            System.out.println("=== Service consuming the configs ===");
            System.out.println(service.describe());
        }
    }
}
