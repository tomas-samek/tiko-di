package io.tiko.examples.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.Test;

/**
 * Pins the lifecycle ordering the framework's auto JVM shutdown hook relies on (#92):
 * {@code ApplicationEndingEvent} handlers (where you stop the HTTP server) run before any
 * {@code @PreDestroy}. "Simulates JVM exit" by calling {@code container.shutdown()} — exactly
 * what the registered hook invokes on {@code Ctrl+C} / {@code SIGTERM}.
 */
class ShutdownSequenceTest {

    @Test
    void applicationEndingHandlerRunsBeforePreDestroy() {
        ShutdownSequenceProbe probe;
        try (Container container = Tiko.create(OnAFreePort.options().build())) {
            probe = container.get(ShutdownSequenceProbe.class);
            container.shutdown();
        }
        assertThat(probe.order()).containsExactly("app-ending", "pre-destroy");
    }

    @Test
    void closingTheContainerStopsTheHttpServer() {
        int port;
        try (Container container = Tiko.create(OnAFreePort.options().build())) {
            port = container.get(TicketHttpServer.class).port();
        }
        assertThatThrownBy(() -> {
                    try (var socket = new Socket()) {
                        socket.connect(new InetSocketAddress("localhost", port), 1000);
                    }
                })
                .isInstanceOf(ConnectException.class);
    }
}
