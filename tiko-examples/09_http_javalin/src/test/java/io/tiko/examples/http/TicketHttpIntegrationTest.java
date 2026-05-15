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

    @Test
    void getReturnsTicketAfterPostAndPerRequestIdsAreDistinct() throws Exception {
        var recorder = container.get(TicketCreatedRecorder.class);
        int eventsBefore = recorder.events().size();

        // POST twice
        HttpResponse<String> first = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"a\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"b\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);

        var emitted = recorder.events();
        assertThat(emitted).hasSize(eventsBefore + 2);

        var lastTwo = emitted.subList(emitted.size() - 2, emitted.size());
        assertThat(lastTwo.get(0).requestId())
                .as("each request gets its own REQUEST-scoped requestId")
                .isNotEqualTo(lastTwo.get(1).requestId());

        // GET back the first one.
        String firstId = JSON.readTree(first.body()).get("id").asText();
        HttpResponse<String> got = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets/" + firstId))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(got.body()).get("title").asText()).isEqualTo("a");
    }

    @Test
    void getReturns404ForUnknownIdAndDoesNotFireDomainEvent() throws Exception {
        var metrics = container.get(MetricsCounter.class);
        var recorder = container.get(TicketCreatedRecorder.class);
        long metricsBefore = metrics.count();
        int eventsBefore = recorder.events().size();

        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets/" + java.util.UUID.randomUUID()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(metrics.count()).as("read path must not fire TicketCreated").isEqualTo(metricsBefore);
        assertThat(recorder.events()).hasSize(eventsBefore);
    }

    @Test
    void lifecycleEventsFireForEveryHttpRequest() throws Exception {
        var timer = container.get(RequestTimer.class);
        int startedBefore = timer.startedCount();
        int endedBefore = timer.endedCount();

        // Three requests: 1 POST, 1 GET success, 1 GET 404. The framework
        // publishes RequestStarted + RequestEnding for each — three of each.
        HttpResponse<String> post = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"lifecycle\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(201);

        String createdId = JSON.readTree(post.body()).get("id").asText();
        HttpResponse<String> getOk = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets/" + createdId))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(getOk.statusCode()).isEqualTo(200);

        HttpResponse<String> get404 = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets/" + java.util.UUID.randomUUID()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(get404.statusCode()).isEqualTo(404);

        assertThat(timer.startedCount() - startedBefore).isEqualTo(3);
        assertThat(timer.endedCount() - endedBefore).isEqualTo(3);
    }
}
