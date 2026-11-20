package io.tiko.examples.quickstart;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end: real container, real Javalin on a random port, real HTTP. The
 * assertions prove the orchestrator chain — POST → JDBC row → event delivered
 * to {@link NoteAuditor} — works without any wrapper between the user code
 * and Hikari / Javalin. The container starts the server: the test only reads
 * its port.
 */
class QuickstartIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JDBC_URL = "jdbc:h2:mem:quickstart;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Container container;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        container = Tiko.create(onPort(0).build());
        port = container.get(HttpServer.class).port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        // The client's idle keep-alive connections would hold the server's graceful stop for about a second.
        if (client != null) client.close();
        if (container != null) container.shutdown();
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement()) {
            st.execute("DELETE FROM note_tags");
            st.execute("DELETE FROM notes");
        }
    }

    @Test
    void postCreatesNoteAndDeliversEvent() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/notes"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"hello orchestrator\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(resp.body());
        UUID id = UUID.fromString(body.get("id").asText());
        assertThat(body.get("text").asText()).isEqualTo("hello orchestrator");

        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                var st = selectById(c, "SELECT text FROM notes WHERE id = ?", id);
                var rs = st.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("hello orchestrator");
        }

        // EventBus.publish on a non-async handler delivers synchronously, so the
        // auditor count is already incremented by the time POST returns 201.
        assertThat(container.get(NoteAuditor.class).count()).isEqualTo(1);
    }

    /** One request, one transaction: the note and its tags are committed together. */
    @Test
    void aRequestCommitsEveryWriteItMade() throws Exception {
        HttpResponse<String> resp = post("{\"text\":\"tagged\",\"tags\":[\"work\",\"urgent\"]}");

        assertThat(resp.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(JSON.readTree(resp.body()).get("id").asText());
        assertThat(count("SELECT COUNT(*) FROM notes WHERE id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM note_tags WHERE note_id = ?", id)).isEqualTo(2);

        HttpResponse<String> get = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/notes/" + id))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(JSON.readTree(get.body()).get("tags"))
                .extracting(JsonNode::asText)
                .containsExactly("urgent", "work");
    }

    /**
     * A request that fails after its first write rolls every write back: the note row is inserted
     * before the blank tag is found, and neither the note nor its first tag stays.
     */
    @Test
    void aRequestThatFailsAfterAWriteRollsEveryWriteBack() throws Exception {
        HttpResponse<String> resp = post("{\"text\":\"half done\",\"tags\":[\"work\",\" \"]}");

        assertThat(resp.statusCode()).isEqualTo(400);
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                var st = c.createStatement();
                var rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM notes) + (SELECT COUNT(*) FROM note_tags)")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).as("rows left by the failed request").isZero();
        }
    }

    private static int count(String sql, Object id) throws SQLException {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                var st = selectById(c, sql, id);
                var rs = st.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void twoRequestsRunInTwoUnits() throws Exception {
        HttpResponse<String> first = post("{\"text\":\"first\"}");
        HttpResponse<String> second = post("{\"text\":\"second\"}");

        String firstUnit = first.headers().firstValue("X-Request-Id").orElseThrow();
        String secondUnit = second.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(firstUnit).isNotEqualTo(secondUnit);
        // The auditor handles NoteCreated synchronously, inside the request's unit, so it reads
        // the same RequestId instance the route read.
        assertThat(container.get(NoteAuditor.class).requestIds()).containsExactly(firstUnit, secondUnit);
    }

    /** The application's config with the server on {@code port}; {@code 0} lets the OS pick one. */
    static TikoOptions.Builder onPort(int port) {
        return TikoOptions.builder()
                .configSource(ConfigSources.layered(
                        ConfigSources.classpath("application.yml"),
                        ConfigSources.fromMap(Map.of("app", Map.of("server", Map.of("port", port))))));
    }

    private HttpResponse<String> post(String json) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/notes"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getReturnsStoredNote() throws Exception {
        HttpResponse<String> post = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/notes"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"round trip\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String id = JSON.readTree(post.body()).get("id").asText();

        HttpResponse<String> get = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/notes/" + id))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(get.body()).get("text").asText()).isEqualTo("round trip");
    }

    @Test
    void getReturns404ForUnknownId() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/notes/" + UUID.randomUUID()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(404);
    }

    @Test
    void postRejectsBlankText() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/notes"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(400);
    }

    /** Looks a row up by id with a bound parameter — test code gets copied, so it never concatenates SQL (#523). */
    private static PreparedStatement selectById(Connection c, String sql, Object id) throws SQLException {
        var st = c.prepareStatement(sql);
        try {
            st.setObject(1, id);
            return st;
        } catch (SQLException e) {
            st.close();
            throw e;
        }
    }
}
