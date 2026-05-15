package io.tiko.examples.persistence.infra;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
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
 * Loads {@code schema.sql} from the classpath and executes it at container
 * start. Idempotent: the script uses {@code CREATE TABLE IF NOT EXISTS}.
 *
 * <p>Production setups should use Flyway or Liquibase instead — this is a
 * teaching simplification, deliberately minimal.
 */
@Component(scope = Scope.SINGLETON)
public class SchemaInitializer {

    private final DataSource ds;

    @Inject
    public SchemaInitializer(DataSource ds) {
        this.ds = ds;
    }

    @PostConstruct
    public void initialize() {
        String script;
        try (InputStream in = SchemaInitializer.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IllegalStateException("schema.sql not found on classpath");
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                script = reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read schema.sql", e);
        }
        try (Connection c = ds.getConnection();
                Statement st = c.createStatement()) {
            for (String stmt : script.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) st.execute(trimmed);
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to execute schema.sql", e);
        }
    }
}
