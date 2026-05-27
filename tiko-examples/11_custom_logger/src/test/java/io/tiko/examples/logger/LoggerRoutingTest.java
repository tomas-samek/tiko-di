package io.tiko.examples.logger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Pins the slf4j-jdk-platform-logging routing contract for #150: framework log calls
 * routed through {@code System.Logger} must reach logback when the SPI is on the
 * system classpath.
 *
 * <p>Forks a fresh JVM with the test process's classpath (which includes
 * {@code slf4j-jdk-platform-logging} and {@code logback-classic}) so the JDK's
 * {@code ServiceLoader} sees the {@code System.LoggerFinder} provider before any
 * {@code System.getLogger} call binds the finder. Running in-process would let the
 * harness's pre-bound JUL finder win — that's the same trap {@code mvn exec:java}
 * falls into, which is exactly why this example needs a forked invocation.
 */
class LoggerRoutingTest {

    @Test
    void frameworkWarningsFlowThroughLogback() throws Exception {
        var classpath = System.getProperty("java.class.path");
        var javaBin = System.getProperty("java.home") + "/bin/java";

        var pb = new ProcessBuilder(javaBin, "-cp", classpath, Main.class.getName());
        pb.redirectErrorStream(true);
        var proc = pb.start();

        var output = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        boolean exited = proc.waitFor(20, TimeUnit.SECONDS);
        assertThat(exited).as("Main must finish within 20s").isTrue();
        assertThat(proc.exitValue()).as("Main must exit cleanly").isZero();

        var combined = String.join("\n", output);
        assertThat(combined)
                .as(
                        "logback's '%%-5level [%%logger{30}]' pattern must appear — proves SLF4JSystemLoggerFinder won the SPI lookup")
                .contains("WARN  [io.tiko.events]");
        assertThat(combined)
                .as("JUL's default WARNING prefix must NOT appear — that would mean the SPI didn't bind")
                .doesNotContain("WARNING: @PreDestroy");
        assertThat(combined).contains("[main] container closed cleanly");
    }

    @Test
    void frameworkWarningAppearsExactlyOnce() throws Exception {
        // #116: a teardown failure routes solely through DefaultErrorHandler — exactly one WARN
        // line, not the previous catch-site + ErrorHandler duplicate.
        var classpath = System.getProperty("java.class.path");
        var javaBin = System.getProperty("java.home") + "/bin/java";

        var pb = new ProcessBuilder(javaBin, "-cp", classpath, Main.class.getName());
        pb.redirectErrorStream(true);
        var proc = pb.start();

        var output = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        proc.waitFor(20, TimeUnit.SECONDS);

        List<String> warnLines = output.stream()
                .filter(s -> s.startsWith("WARN  [io.tiko.events]"))
                .toList();
        assertThat(warnLines)
                .as("a teardown failure must log exactly one WARN line (#116)")
                .hasSize(1);
    }
}
