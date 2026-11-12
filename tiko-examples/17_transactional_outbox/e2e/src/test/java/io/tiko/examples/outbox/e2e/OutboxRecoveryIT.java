package io.tiko.examples.outbox.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.examples.outbox.Database;
import io.tiko.examples.outbox.Messages;
import io.tiko.examples.outbox.Messages.OrderPlaced;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class OutboxRecoveryIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String PORT_LINE = "outbox-orders HTTP port ";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.2")).withStartupAttempts(3);

    @TempDir
    Path directory;

    @Test
    void recoversCommittedOrderAfterProcessDiesAndDeduplicatesReplay() throws Exception {
        String bootstrap = KAFKA.getBootstrapServers();
        String orderDb = jdbcUrl("orders");
        String warehouseDb = jdbcUrl("warehouse");
        UUID orderId = UUID.randomUUID();

        try (var admin = Admin.create(Map.of("bootstrap.servers", bootstrap));
                var http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build()) {
            admin.createTopics(List.of(
                            new NewTopic(Messages.ORDERS, 1, (short) 1), new NewTopic(Messages.RESULTS, 1, (short) 1)))
                    .all()
                    .get(30, TimeUnit.SECONDS);

            try (var disconnected = start("orders", "orders-disconnected", orderDb, "127.0.0.1:1")) {
                URI orderUri = disconnected.orderUri(orderId);
                var accepted = send(
                        http,
                        HttpRequest.newBuilder(orderUri)
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"sku":"widget","quantity":2}
                                """)));
                assertThat(accepted.statusCode()).isEqualTo(202);
                assertThat(accepted.body()).contains("PENDING");

                // No route to this test's Kafka broker. Force process death after the HTTP
                // response proves the database commit completed, without running shutdown hooks.
                disconnected.kill();
            }
            assertThat(scalar(orderDb, "SELECT COUNT(*) FROM orders WHERE status = 'PENDING'"))
                    .isEqualTo(1);
            assertThat(scalar(orderDb, "SELECT COUNT(*) FROM outbox")).isEqualTo(1);

            try (var warehouse = start("warehouse", "warehouse", warehouseDb, bootstrap);
                    var orders = start("orders", "orders-restarted", orderDb, bootstrap)) {
                URI orderUri = orders.orderUri(orderId);
                await().atMost(TIMEOUT).untilAsserted(() -> {
                    var response = send(http, HttpRequest.newBuilder(orderUri).GET());
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).contains("RESERVED");
                });

                long duplicateOffset = publishDuplicate(bootstrap, new OrderPlaced(orderId, "widget", 2));
                await().atMost(TIMEOUT).untilAsserted(() -> {
                    var offsets = admin.listConsumerGroupOffsets("outbox-warehouse")
                            .partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS);
                    assertThat(offsets.get(new TopicPartition(Messages.ORDERS, 0)))
                            .isNotNull();
                    assertThat(offsets.get(new TopicPartition(Messages.ORDERS, 0))
                                    .offset())
                            .isGreaterThan(duplicateOffset);
                });
            }
            assertThat(scalar(warehouseDb, "SELECT available FROM stock WHERE sku = 'widget'"))
                    .isEqualTo(8);
            assertThat(scalar(warehouseDb, "SELECT COUNT(*) FROM reservations")).isEqualTo(1);
        }
    }

    private String jdbcUrl(String service) {
        return "jdbc:h2:file:" + directory.resolve(service).toAbsolutePath() + ";WRITE_DELAY=0";
    }

    private Service start(String service, String name, String url, String bootstrap) throws IOException {
        var target = Path.of("..", service, "target");
        Path jar;
        try (var files = Files.list(target)) {
            jar = files.filter(path -> path.getFileName().toString().matches("outbox-" + service + "-[0-9].*\\.jar"))
                    .filter(path -> !path.getFileName().toString().matches(".*-(sources|javadoc|tests)\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No runnable JAR in " + target));
        }

        Path log = directory.resolve(name + ".log");
        var command = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString());
        command.redirectErrorStream(true).redirectOutput(log.toFile());
        command.environment().put("KAFKA_BOOTSTRAP", bootstrap);
        command.environment().put(service.equals("orders") ? "ORDERS_DB_URL" : "WAREHOUSE_DB_URL", url);
        command.environment().put("ORDERS_HTTP_PORT", "0");
        return new Service(command.start(), log);
    }

    private static HttpResponse<String> send(HttpClient http, HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return http.send(request.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long publishDuplicate(String bootstrap, OrderPlaced order) throws Exception {
        var properties = Map.<String, Object>of("bootstrap.servers", bootstrap, "max.block.ms", "5000");
        try (var producer = new KafkaProducer<>(properties, new StringSerializer(), new ByteArraySerializer())) {
            var record = new ProducerRecord<>(
                    Messages.ORDERS, order.orderId().toString(), new JsonKafkaSerializer().serialize(order));
            return producer.send(record).get(10, TimeUnit.SECONDS).offset();
        }
    }

    private static int scalar(String url, String sql) throws SQLException {
        try (var connection = Database.open(url).getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private record Service(Process process, Path log) implements AutoCloseable {
        URI orderUri(UUID orderId) {
            String address = await().atMost(TIMEOUT)
                    .until(
                            () -> {
                                String output = Files.readString(log);
                                if (!process.isAlive()) {
                                    throw new IllegalStateException("Orders process exited. Log:\n" + output);
                                }
                                return output.lines()
                                        .filter(line -> line.startsWith(PORT_LINE))
                                        .map(line -> line.substring(PORT_LINE.length()))
                                        .findFirst();
                            },
                            java.util.Optional::isPresent)
                    .orElseThrow();
            return URI.create("http://127.0.0.1:" + address + "/orders/" + orderId);
        }

        void kill() throws InterruptedException {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(15, TimeUnit.SECONDS))
                        .as("service process stopped")
                        .isTrue();
            }
        }

        @Override
        public void close() throws InterruptedException {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    kill();
                }
            }
        }
    }
}
