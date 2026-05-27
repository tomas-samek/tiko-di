package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.PreDestroy;
import io.tiko.events.ApplicationEndingEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Demonstrates the #92 cleanup pattern: with the framework's auto-registered JVM shutdown hook,
 * resource teardown hangs off {@code ApplicationEndingEvent} — the natural place to stop the HTTP
 * server ({@code javalinApp.stop()}) — and runs <em>before</em> any {@code @PreDestroy}, with no
 * manual {@code Runtime.addShutdownHook}. The recorded order proves that ordering.
 */
@Component(scope = Scope.SINGLETON)
public class ShutdownSequenceProbe {

    private final List<String> order = new CopyOnWriteArrayList<>();

    @EventHandler
    public void onApplicationEnding(ApplicationEndingEvent event) {
        order.add("app-ending"); // e.g. javalinApp.stop() — drain the HTTP layer first
    }

    @PreDestroy
    public void close() {
        order.add("pre-destroy"); // bean teardown happens after the ending event
    }

    public List<String> order() {
        return List.copyOf(order);
    }
}
