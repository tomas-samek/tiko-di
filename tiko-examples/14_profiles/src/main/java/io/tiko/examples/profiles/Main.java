package io.tiko.examples.profiles;

import io.tiko.Container;
import io.tiko.runtime.Tiko;

/**
 * Demonstrates {@link io.tiko.annotations.Component#profiles()} — compile-time
 * selection of one of several impls keyed by profile name.
 *
 * <p>The generated container only contains components whose declared profile
 * matches the {@code -Atiko.profiles=...} annotation processor argument passed
 * at build time. This module's pom.xml ships two Maven profiles (`dev`, `prod`)
 * that set that flag; pick one before running.
 *
 * <p>Profile selection is a <em>build flag</em>, not a runtime switch — consistent
 * with Tiko's compile-time-DI design (no classpath scanning, no conditional
 * factories). Switching profiles means re-compiling.
 */
public class Main {
    public static void main(String[] args) {
        try (Container container = Tiko.create()) {
            GreetingService service = container.get(GreetingService.class);
            System.out.println("=== Profile-selected Greeter impl ===");
            System.out.println("Welcome:    " + service.welcome("world"));
        }
    }
}
