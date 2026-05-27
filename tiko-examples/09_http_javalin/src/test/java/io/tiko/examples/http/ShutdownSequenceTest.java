package io.tiko.examples.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
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
        try (Container container = Tiko.create()) {
            probe = container.get(ShutdownSequenceProbe.class);
            container.shutdown();
        }
        assertThat(probe.order()).containsExactly("app-ending", "pre-destroy");
    }
}
