package io.tiko.examples.quickstart;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end: real container, real Javalin on a random port, real HTTP. The
 * assertions prove the orchestrator chain — POST → JDBC row → event delivered
 * to {@link NoteAuditor} — works without any wrapper between the user code
 * and Hikari / Javalin.
 */
class QuickstartIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JDBC_URL = "jdbc:h2:mem:quickstart;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Container container;
    private Javalin app;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        container = Tiko.create(ConfigSources.classpath("application.yml"));
        var routes = new NoteRoutes(container.get(NoteRepository.class), container.getEventBus());
        app = container.get(Javalin.class);
        app.post("/notes", routes::handleCreate);
        app.get("/notes/{id}", routes::handleGet);
        app.start(0);
        port = app.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (container != null) container.shutdown();
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement()) {
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
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT text FROM notes WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("hello orchestrator");
        }

        // EventBus.publish on a non-async handler delivers synchronously, so the
        // auditor count is already incremented by the time POST returns 201.
        assertThat(container.get(NoteAuditor.class).count()).isEqualTo(1);
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
}
