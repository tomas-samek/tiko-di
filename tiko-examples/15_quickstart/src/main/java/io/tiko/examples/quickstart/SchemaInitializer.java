package io.tiko.examples.quickstart;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.events.ApplicationStartedEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Lifecycle-event-hook recipe — runs once after the container is fully wired
 * but before {@link io.tiko.runtime.Tiko#create} returns to the caller. The skill
 * cites this shape for the Flyway recipe: swap the raw DDL below for
 * {@code Flyway.configure().dataSource(ds).load().migrate()} and the pattern
 * is identical.
 */
@Component(scope = Scope.SINGLETON)
public class SchemaInitializer {

    private final DataSource ds;

    @Inject
    public SchemaInitializer(DataSource ds) {
        this.ds = ds;
    }

    @EventHandler
    public void onApplicationStarted(ApplicationStartedEvent event) throws SQLException, IOException {
        String script;
        try (InputStream in = SchemaInitializer.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IllegalStateException("schema.sql not found on classpath");
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                script = reader.lines().collect(Collectors.joining("\n"));
            }
        }
        try (Connection c = ds.getConnection();
                Statement st = c.createStatement()) {
            for (String stmt : script.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) st.execute(trimmed);
            }
        }
    }
}
