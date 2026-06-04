package io.tiko.examples.config;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import io.tiko.runtime.Tiko;

/**
 * Companion to {@link Main} that demonstrates the fail-fast contract: when YAML
 * fails to bind against the declared {@link io.tiko.annotations.Configuration} records,
 * {@link Tiko#create} throws {@link ConfigValidationException} <em>before</em> the
 * container is constructed. No half-bound configuration ever reaches application code.
 *
 * <p>Load {@code broken-config.yaml}, which omits the required {@code db.url} field, and
 * print the structured error report. Exits non-zero so a CI step that wraps this would
 * correctly flag a misconfiguration.
 */
public class BrokenConfigMain {
    public static void main(String[] args) {
        System.out.println("=== Booting Tiko against broken-config.yaml (db.url missing) ===");
        System.out.println();
        try (Container container = Tiko.create(ConfigSources.classpath("broken-config.yaml"))) {
            System.out.println("Unreachable: boot should have failed before reaching this line.");
        } catch (ConfigValidationException e) {
            System.out.println("Caught ConfigValidationException — container was NOT constructed.");
            System.out.println();
            System.out.println("--- Error report (from e.getMessage()) ---");
            System.out.println(e.getMessage());
            System.out.println("--- end of report ---");
            System.out.println();
            System.out.println("Structured issues (from e.issues()):");
            e.issues().forEach(issue -> System.out.println("  - " + issue.code() + ": " + issue.description()));
            System.exit(1);
        }
    }
}
