package io.tiko.examples.kafka.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots Kafka via Testcontainers, then forks each service's shaded jar as a separate
 * JVM, wired to the broker via the {@code KAFKA_BOOTSTRAP} env var. Asserts an
 * {@code OrderPlaced} placed through order-service's stdin appears in warehouse-service's
 * probe file within a deadline.
 *
 * <p>Each subprocess's stdout is drained on a daemon thread so (a) the OS pipe buffer
 * can't fill up and stall the subprocess, and (b) the test can observe each service's
 * "ready" line before proceeding to the next phase — the previous "sleep 2 seconds and
 * hope the consumer-group join finished" pattern timed out reliably under cold-start CI
 * (#149).
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderToWarehouseE2EIT {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private Path probeFile;
    private Process orderProc;
    private Process warehouseProc;

    @BeforeEach
    void setUp() throws Exception {
        probeFile = Files.createTempFile("warehouse-probe", ".log");
        Files.deleteIfExists(probeFile);
    }

    @AfterEach
    void tearDown() {
        if (orderProc != null) orderProc.destroy();
        if (warehouseProc != null) warehouseProc.destroy();
    }

    @Test
    void order_placed_in_order_service_reaches_warehouse_service() throws Exception {
        var orderJar = jarPath("order-service");
        var warehouseJar = jarPath("warehouse-service");
        var bootstrapServers = KAFKA.getBootstrapServers();

        // --- warehouse-service ---
        var warehousePb = new ProcessBuilder("java", "-Dprobe.file=" + probeFile.toAbsolutePath(), "-jar", warehouseJar)
                .redirectErrorStream(true);
        warehousePb.environment().put("KAFKA_BOOTSTRAP", bootstrapServers);
        warehouseProc = warehousePb.start();

        var warehouseReady = new AtomicBoolean(false);
        drainOnDaemonThread("warehouse", warehouseProc.getInputStream(), line -> {
            if (line.contains("warehouse-service ready")) warehouseReady.set(true);
        });
        await().atMost(READY_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .alias("warehouse-service ready line on stdout")
                .untilTrue(warehouseReady);

        // --- order-service ---
        var orderPb = new ProcessBuilder("java", "-jar", orderJar).redirectErrorStream(true);
        orderPb.environment().put("KAFKA_BOOTSTRAP", bootstrapServers);
        orderProc = orderPb.start();

        var orderReady = new AtomicBoolean(false);
        drainOnDaemonThread("order", orderProc.getInputStream(), line -> {
            if (line.contains("order-service ready")) orderReady.set(true);
        });
        await().atMost(READY_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .alias("order-service ready line on stdout")
                .untilTrue(orderReady);

        // Place an order via stdin. Closing stdin lets order-service exit cleanly after
        // publishing, which flushes the Kafka producer.
        try (var out = orderProc.getOutputStream()) {
            out.write("19.99\n".getBytes(StandardCharsets.UTF_8));
        }

        // Probe file is the externally-observable signal that warehouse-service consumed
        // the Kafka message and routed it through @EventHandler.
        await().atMost(PROBE_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .alias("warehouse probe file becomes non-blank")
                .until(() ->
                        Files.exists(probeFile) && !Files.readString(probeFile).isBlank());

        assertThat(Files.readString(probeFile)).isNotBlank();
    }

    /**
     * Drains the subprocess's stdout on a daemon thread — prevents the OS pipe buffer
     * from filling up and stalling the subprocess, and feeds each line to {@code onLine}
     * so the test can react to specific log lines (e.g., a "ready" signal).
     */
    private static void drainOnDaemonThread(String label, InputStream stream, Consumer<String> onLine) {
        Thread t = new Thread(
                () -> {
                    try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            // Mirror to test stdout for debuggability under CI.
                            System.out.println("[" + label + "] " + line);
                            onLine.accept(line);
                        }
                    } catch (java.io.IOException ignored) {
                        // Subprocess pipe closed; reader thread exits.
                    }
                },
                "e2e-drain-" + label);
        t.setDaemon(true);
        t.start();
    }

    private static String jarPath(String moduleName) {
        // The reactor builds each service's shaded jar at <module>/target/*.jar before e2e runs.
        Path target = Path.of("..", moduleName, "target");
        try {
            return Files.list(target)
                    .filter(p -> p.toString().endsWith(".jar") && !p.toString().contains("original-"))
                    .map(Path::toString)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No shaded jar in " + target));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
