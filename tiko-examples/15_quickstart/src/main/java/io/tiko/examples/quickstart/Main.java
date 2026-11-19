package io.tiko.examples.quickstart;

import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;

/**
 * Canonical orchestrator-model bootstrap. {@link Tiko#daemon} builds the container from the typed
 * {@link AppConfig}: the produced {@code DataSource} creates the schema, and {@link HttpServer}
 * binds its port. A failure there, such as a port already in use, is thrown from here and the
 * process exits with it. The daemon's JVM hook closes the container on {@code Ctrl+C} /
 * {@code SIGTERM}, which stops the server first.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Tiko.daemon(ConfigSources.classpath("application.yml")).awaitShutdown();
    }
}
