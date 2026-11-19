package io.tiko.examples.outbox.orders;

import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;

/**
 * The container builds and starts everything: the database with its schema, the outbox relay, the
 * Kafka consumer and {@link OrdersHttpServer}. A failure there is thrown from here, and the process
 * exits. {@code Ctrl+C} / {@code SIGTERM} closes the container, which stops the server first.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Tiko.daemon(ConfigSources.classpath("orders.yaml")).awaitShutdown();
    }
}
