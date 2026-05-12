package io.tiko.examples.kafka.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 */
@Testcontainers
class OrderToWarehouseE2ETest {

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
        String orderJar = jarPath("order-service");
        String warehouseJar = jarPath("warehouse-service");

        String bootstrapServers = KAFKA.getBootstrapServers();

        ProcessBuilder warehousePb = new ProcessBuilder(
                        "java", "-Dprobe.file=" + probeFile.toAbsolutePath(), "-jar", warehouseJar)
                .inheritIO();
        warehousePb.environment().put("KAFKA_BOOTSTRAP", bootstrapServers);
        warehouseProc = warehousePb.start();

        // Give the warehouse a moment to subscribe.
        Thread.sleep(2_000);

        ProcessBuilder orderPb = new ProcessBuilder("java", "-jar", orderJar).redirectErrorStream(true);
        orderPb.environment().put("KAFKA_BOOTSTRAP", bootstrapServers);
        orderProc = orderPb.start();

        // Feed a price to order-service's stdin to place an order.
        try (OutputStream out = orderProc.getOutputStream()) {
            out.write("19.99\n".getBytes(StandardCharsets.UTF_8));
        }

        await().atMost(Duration.ofSeconds(30))
                .until(() ->
                        Files.exists(probeFile) && !Files.readString(probeFile).isBlank());

        String probeContent = Files.readString(probeFile);
        assertThat(probeContent).isNotBlank();
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
