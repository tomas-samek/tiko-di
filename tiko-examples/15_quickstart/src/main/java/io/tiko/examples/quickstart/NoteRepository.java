package io.tiko.examples.quickstart;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Raw JDBC against a HikariCP-backed {@link DataSource} — no wrapper between
 * user code and the driver. Each method opens its own connection and lets the
 * pool reclaim it on close.
 */
@Component(scope = Scope.SINGLETON)
public class NoteRepository {

    private static final String INSERT = "INSERT INTO notes (id, text, created_at) VALUES (?, ?, ?)";
    private static final String SELECT = "SELECT id, text, created_at FROM notes WHERE id = ?";

    private final DataSource ds;

    @Inject
    public NoteRepository(DataSource ds) {
        this.ds = ds;
    }

    public void insert(Note note) throws SQLException {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, note.id());
            ps.setString(2, note.text());
            ps.setTimestamp(3, Timestamp.from(note.createdAt()));
            ps.executeUpdate();
        }
    }

    public Optional<Note> findById(UUID id) throws SQLException {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(SELECT)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Note(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getTimestamp(3).toInstant()));
            }
        }
    }
}
