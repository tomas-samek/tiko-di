package io.tiko.examples.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.runtime.Tiko;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test: real Tiko container + real Javalin server on a
 * random port + real HTTP via {@link HttpClient}. Each test sets up and tears
 * down per-test for isolation.
 */
class TicketHttpIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Container container;
    private Javalin app;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        container = Tiko.create();
        var routes = new TicketHttpRoutes(container.get(TicketService.class), container.getEventBus(), container);
        app = Javalin.create();
        app.post("/tickets", TikoJavalin.scoped(container, routes::handleCreate));
        app.get("/tickets/{id}", TikoJavalin.scoped(container, routes::handleGet));
        app.start(0); // 0 = OS picks a free port
        port = app.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (container != null) container.shutdown();
    }

    @Test
    void postCreatesTicketAndReturns201() throws Exception {
        var notifications = container.get(NotificationSender.class).expectNotifications(1);
        var metrics = container.get(MetricsCounter.class);
        long countBefore = metrics.count();

        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"first ticket\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(resp.body());
        assertThat(body.get("id").asText()).isNotBlank();
        assertThat(body.get("title").asText()).isEqualTo("first ticket");

        assertThat(metrics.count()).isEqualTo(countBefore + 1);

        boolean fired = notifications.await(5, TimeUnit.SECONDS);
        assertThat(fired).as("async NotificationSender ran").isTrue();
    }
}
