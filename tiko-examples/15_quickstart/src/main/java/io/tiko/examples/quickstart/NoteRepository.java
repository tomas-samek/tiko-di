package io.tiko.examples.quickstart;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw JDBC on the unit of work's {@link Connection}: no wrapper between user code and the
 * driver. The connection is EVENT-scoped, so this SINGLETON gets a generated proxy that resolves
 * the current unit's connection on every call; every statement of one request runs on one
 * connection, in that request's transaction ({@link RequestTransactions}).
 */
@Component(scope = Scope.SINGLETON)
public class NoteRepository {

    private static final String INSERT = "INSERT INTO notes (id, text, created_at) VALUES (?, ?, ?)";
    private static final String INSERT_TAG = "INSERT INTO note_tags (note_id, tag) VALUES (?, ?)";
    private static final String SELECT = "SELECT id, text, created_at FROM notes WHERE id = ?";
    private static final String SELECT_TAGS = "SELECT tag FROM note_tags WHERE note_id = ? ORDER BY tag";

    private final Connection connection;

    @Inject
    public NoteRepository(Connection connection) {
        this.connection = connection;
    }

    public void insert(Note note) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
            ps.setObject(1, note.id());
            ps.setString(2, note.text());
            ps.setTimestamp(3, Timestamp.from(note.createdAt()));
            ps.executeUpdate();
        }
    }

    public void insertTag(UUID noteId, String tag) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_TAG)) {
            ps.setObject(1, noteId);
            ps.setString(2, tag);
            ps.executeUpdate();
        }
    }

    public Optional<Note> findById(UUID id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Note(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(), tagsOf(id)));
            }
        }
    }

    private List<String> tagsOf(UUID id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_TAGS)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                var tags = new ArrayList<String>();
                while (rs.next()) tags.add(rs.getString(1));
                return List.copyOf(tags);
            }
        }
    }
}
