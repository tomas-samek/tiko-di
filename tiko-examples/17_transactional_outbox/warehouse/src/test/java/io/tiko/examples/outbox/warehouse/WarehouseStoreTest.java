package io.tiko.examples.outbox.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.tiko.config.ConfigSources;
import io.tiko.examples.outbox.Database;
import io.tiko.examples.outbox.Messages;
import io.tiko.examples.outbox.Messages.Decision;
import io.tiko.examples.outbox.Messages.OrderPlaced;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.kafka.test.FakeKafkaTransport;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarehouseStoreTest {
    @Test
    void generatedKafkaBridgeRecordsReservationAndResultOutbox() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("warehouse");
        var database = Database.open(url);
        var broker = new FakeKafkaBroker();
        var options = TikoOptions.builder()
                .configSource(ConfigSources.fromMap(Map.of("outbox", Map.of("dbUrl", url))))
                .replaceTransport(KafkaTransport.class, transport -> FakeKafkaTransport.over(transport, broker))
                .build();

        try (var container = Tiko.create(options)) {
            var order = new OrderPlaced(UUID.randomUUID(), "widget", 2);
            broker.produce(Messages.ORDERS, new JsonKafkaSerializer().serialize(order));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(scalar(database, "SELECT available FROM stock WHERE sku = 'widget'"))
                        .isEqualTo(8);
                assertThat(scalar(database, "SELECT COUNT(*) FROM outbox")).isEqualTo(1);
            });
        }
    }

    @TempDir
    Path directory;

    @Test
    void redeliveryAfterRestartDoesNotReserveStockOrEnqueueAgain() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("warehouse");
        var warehouse = new WarehouseStore(Database.open(url));
        warehouse.initialize();
        var order = new OrderPlaced(UUID.randomUUID(), "widget", 2);

        var result = warehouse.reserve(order);
        assertThat(result.decision()).isEqualTo(Decision.RESERVED);

        var database = Database.open(url);
        var restarted = new WarehouseStore(database);
        restarted.initialize();
        assertThat(restarted.reserve(order)).isEqualTo(result);
        assertThat(scalar(database, "SELECT available FROM stock WHERE sku = 'widget'"))
                .isEqualTo(8);
        assertThat(scalar(database, "SELECT COUNT(*) FROM reservations")).isEqualTo(1);
        assertThat(scalar(database, "SELECT COUNT(*) FROM outbox")).isEqualTo(1);

        var tooLarge = new OrderPlaced(UUID.randomUUID(), "widget", 9);
        assertThat(restarted.reserve(tooLarge).decision()).isEqualTo(Decision.REJECTED);
        assertThat(scalar(database, "SELECT available FROM stock WHERE sku = 'widget'"))
                .isEqualTo(8);
    }

    @Test
    void missingOutboxTableRollsBackStockAndReservation() throws Exception {
        var database = Database.open("jdbc:h2:file:" + directory.resolve("warehouse"));
        var warehouse = new WarehouseStore(database);
        warehouse.initialize();
        Database.initialize(database, "DROP TABLE outbox");

        assertThatThrownBy(() -> warehouse.reserve(new OrderPlaced(UUID.randomUUID(), "widget", 2)))
                .isInstanceOf(SQLException.class);
        assertThat(scalar(database, "SELECT available FROM stock WHERE sku = 'widget'"))
                .isEqualTo(10);
        assertThat(scalar(database, "SELECT COUNT(*) FROM reservations")).isZero();
    }

    private static int scalar(DataSource database, String sql) throws SQLException {
        try (var connection = database.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
