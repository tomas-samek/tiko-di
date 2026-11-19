package io.tiko.examples.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end verification of #48: the framework's event executor is NOT
 * torn down when the HTTP server stops. {@code container.close()} stops the
 * server first ({@link TicketHttpServer} on {@code ApplicationEndingEvent}),
 * then lets in-flight async event handlers complete within the configured
 * {@code shutdownTimeout} before it returns. Uses the programmatic path
 * ({@code TikoOptions.Builder.shutdownTimeout(...)}) for test isolation
 * — the example's {@code config.yaml} is bypassed here so each test method
 * controls its own timeout.
 */
class HttpAsyncDrainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void slowAsyncHandlerCompletesBeforeContainerCloseReturns() throws Exception {
        TikoOptions opts =
                OnAFreePort.options().shutdownTimeout(Duration.ofSeconds(5)).build();
        Container container = Tiko.create(opts);
        SlowAuditService slowAudit = container.get(SlowAuditService.class);
        CountDownLatch latch = slowAudit.expectOne();

        int port = container.get(TicketHttpServer.class).port();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/tickets"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(new CreateTicketRequest("drain"))))
                .build();
        // The client closes its connection first: an idle keep-alive connection would add about a
        // second to the server's graceful stop, which is not what this test times.
        try (var client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
        }

        long startNanos = System.nanoTime();
        container.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(latch.getCount())
                .as("slow async handler must have completed during close()")
                .isZero();
        assertThat(elapsed)
                .as("close() must not exceed the configured shutdownTimeout window")
                .isLessThan(Duration.ofSeconds(5).plusMillis(500));
    }

    @Test
    void shortTimeoutForcesAsyncHandlerInterruption() throws Exception {
        // Tight timeout: 200ms vs the slow handler's 2s sleep. Close() returns within ~500ms.
        TikoOptions opts =
                OnAFreePort.options().shutdownTimeout(Duration.ofMillis(200)).build();
        Container container = Tiko.create(opts);
        SlowAuditService slowAudit = container.get(SlowAuditService.class);
        CountDownLatch latch = slowAudit.expectOne();

        int port = container.get(TicketHttpServer.class).port();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/tickets"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(new CreateTicketRequest("force"))))
                .build();
        // The client closes its connection first: an idle keep-alive connection would add about a
        // second to the server's graceful stop, which is not what this test times.
        try (var client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
        }

        long startNanos = System.nanoTime();
        container.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        // The slow handler sleeps 2s; the 200ms timeout forces an interrupt.
        assertThat(elapsed)
                .as("close() should force termination well before the slow handler finishes")
                .isLessThan(Duration.ofMillis(800));

        // Handler interrupted by Thread.sleep — latch did NOT count down (return path skipped).
        assertThat(latch.await(50, TimeUnit.MILLISECONDS)).isFalse();
    }
}
