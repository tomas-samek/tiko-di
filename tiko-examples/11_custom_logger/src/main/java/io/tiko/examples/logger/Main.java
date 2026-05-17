package io.tiko.examples.logger;

import io.tiko.Container;
import io.tiko.runtime.Tiko;

/**
 * Bootstrap that demonstrates routing tiko's framework logs through slf4j + logback.
 *
 * <p>Drops two deps into {@code pom.xml}:
 *
 * <ul>
 *   <li>{@code slf4j-jdk-platform-logging} — the {@code System.LoggerFinder} bridge.</li>
 *   <li>{@code logback-classic} — the slf4j backend that owns the output format.</li>
 * </ul>
 *
 * <p>Plus a {@code logback.xml} on the classpath with a recognizable pattern. That's it —
 * no tiko-side code needed, no {@code TikoOptions} wiring.
 *
 * <p>This main forces an instantiation of {@link FailingComponent} (whose {@code @PreDestroy}
 * throws), then closes the container. The thrown exception is routed through
 * {@code DefaultErrorHandler}, which logs a WARNING. Logback formats and prints it.
 *
 * <p>Look for {@code WARN [io.tiko.events]} in the output — that prefix proves logback owns
 * the formatting (JUL's default format is recognizably different).
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        try (Container container = Tiko.create()) {
            // Force instantiation so @PreDestroy will fire on close.
            container.get(FailingComponent.class);
        }
        System.out.println("[main] container closed cleanly");
    }
}
