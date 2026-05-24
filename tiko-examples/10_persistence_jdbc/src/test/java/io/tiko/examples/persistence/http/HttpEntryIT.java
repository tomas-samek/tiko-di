package io.tiko.examples.persistence.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.infra.TransactionalScope;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for the HTTP entry point: real Tiko
 * container + real Javalin server on a random port + real HTTP via
 * {@link HttpClient}. The load-bearing assertion in every scenario is
 * a cross-connection JDBC query that proves what was (or wasn't)
 * committed independent of any Tiko-side state.
 */
class HttpEntryIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JDBC_URL = "jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Container container;
    private Javalin app;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        container = Tiko.create(ConfigSources.classpath("application.yml"));
        var routes = new OrderHttpRoutes(container);
        app = Javalin.create();
        app.post(
                "/orders",
                ctx -> TransactionalScope.run(container, () -> {
                    routes.handleCreate(ctx);
                    return null;
                }));
        app.get(
                "/orders/{id}",
                ctx -> TransactionalScope.run(container, () -> {
                    routes.handleGet(ctx);
                    return null;
                }));
        app.start(0);
        port = app.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (app != null) app.stop();
        if (container != null) container.shutdown();
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement()) {
            st.execute("DELETE FROM order_items");
            st.execute("DELETE FROM orders");
        }
    }

    @Test
    void postCreatesOrderAndReturns201() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"customer\":\"alice\",\"items\":[{\"lineNo\":1,\"sku\":\"sku-1\",\"qty\":2}]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(resp.body());
        String id = body.get("id").asText();
        assertThat(body.get("customer").asText()).isEqualTo("alice");

        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT customer FROM orders WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("alice");
        }
    }

    @Test
    void postFailingMidTransactionRollsBackEverything() throws Exception {
        // Negative qty on the second item triggers the bridge's validation
        // AFTER the orders row + first item insert succeeded. The
        // TransactionalScope wrapper rolls back. Nothing must be committed.
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"customer\":\"poison\",\"items\":["
                                + "{\"lineNo\":1,\"sku\":\"a\",\"qty\":1},"
                                + "{\"lineNo\":2,\"sku\":\"b\",\"qty\":-1}]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(500);

        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM orders WHERE customer = 'poison'")) {
            rs.next();
            assertThat(rs.getInt(1)).as("orders row must be rolled back").isEqualTo(0);
        }
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM order_items WHERE sku IN ('a','b')")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("any inserted items must be rolled back")
                    .isEqualTo(0);
        }
    }

    @Test
    void getReturnsCreatedOrder() throws Exception {
        HttpResponse<String> postResp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"customer\":\"carol\",\"items\":[{\"lineNo\":1,\"sku\":\"sku-x\",\"qty\":5}]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(postResp.statusCode()).isEqualTo(201);
        String id = JSON.readTree(postResp.body()).get("id").asText();

        HttpResponse<String> getResp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders/" + id))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(getResp.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(getResp.body());
        assertThat(body.get("customer").asText()).isEqualTo("carol");
        assertThat(body.get("items").get(0).get("sku").asText()).isEqualTo("sku-x");
    }

    @Test
    void getReturns404ForUnknownId() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders/" + java.util.UUID.randomUUID()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
    }
}
