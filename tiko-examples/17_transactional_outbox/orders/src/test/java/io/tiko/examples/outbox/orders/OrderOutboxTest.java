package io.tiko.examples.outbox.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.tiko.config.ConfigSources;
import io.tiko.examples.outbox.Database;
import io.tiko.examples.outbox.JdbcOutbox;
import io.tiko.examples.outbox.Messages;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrderOutboxTest {

    @Test
    void generatedKafkaBridgeSkipsInvalidResultsAndAppliesValidOnes() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("orders");
        var broker = new FakeKafkaBroker();
        var options = TikoOptions.builder()
                .configSource(ConfigSources.fromMap(Map.of("outbox", Map.of("dbUrl", url, "httpPort", 0))))
                .replaceTransport(KafkaTransport.class, transport -> FakeKafkaTransport.over(transport, broker))
                .build();

        try (var container = Tiko.create(options)) {
            var orders = container.get(OrderStore.class);
            var order = new OrderPlaced(UUID.randomUUID(), "widget", 2);
            orders.place(order);
            var result = new Messages.ReservationDecided(order.orderId(), Messages.Decision.RESERVED);
            byte[] payload = new JsonKafkaSerializer().serialize(result);
            var delivered = new AtomicInteger();
            container.getEventBus().subscribe(Messages.ReservationDecided.class, event -> delivered.incrementAndGet());

            var serializer = new JsonKafkaSerializer();
            broker.produce(
                    Messages.RESULTS,
                    serializer.serialize(
                            new Messages.ReservationDecided(UUID.randomUUID(), Messages.Decision.RESERVED)));
            broker.produce(Messages.RESULTS, payload);
            broker.produce(
                    Messages.RESULTS,
                    serializer.serialize(new Messages.ReservationDecided(order.orderId(), Messages.Decision.REJECTED)));
            broker.produce(Messages.RESULTS, payload);
            await().atMost(Duration.ofSeconds(5)).until(() -> delivered.get() == 2);
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(orders.find(order.orderId()))
                            .hasValueSatisfying(
                                    saved -> assertThat(saved.status()).isEqualTo("RESERVED")));
        }
    }

    @TempDir
    Path directory;

    @Test
    void committedOrderIsPublishedAfterRestartAndFailedAcknowledgement() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("orders").toAbsolutePath() + ";WRITE_DELAY=0";
        var orders = new OrderStore(Database.open(url));
        orders.initialize();
        var order = new OrderPlaced(UUID.randomUUID(), "widget", 2);

        assertThat(orders.place(order)).isPresent();
        assertThat(orders.place(order)).isPresent();
        assertThat(orders.place(new OrderPlaced(order.orderId(), "widget", 3))).isEmpty();

        // The original service has committed and has not started a relay. Reopen the same file.
        var reopened = Database.open(url);
        assertThat(new OrderStore(reopened).find(order.orderId())).isPresent();
        var relay = new JdbcOutbox(reopened);
        assertThatThrownBy(() -> relay.publishNext(
                        record -> CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"))))
                .isInstanceOf(ExecutionException.class);

        var broker = new FakeKafkaBroker();
        try (var producer = broker.producerClient()) {
            assertThat(relay.publishNext(producer::send)).isTrue();
            assertThat(relay.publishNext(producer::send)).isFalse();
        }
        assertThat(broker.produced(Messages.ORDERS)).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(order.orderId().toString());
            assertThat(new JsonKafkaSerializer().deserialize(record.value(), OrderPlaced.class))
                    .isEqualTo(order);
        });
    }

    @Test
    void missingOutboxTableRollsBackOrderInsert() throws Exception {
        var database = Database.open("jdbc:h2:file:" + directory.resolve("orders"));
        var orders = new OrderStore(database);
        orders.initialize();
        Database.initialize(database, "DROP TABLE outbox");
        var order = new OrderPlaced(UUID.randomUUID(), "widget", 2);

        assertThatThrownBy(() -> orders.place(order)).isInstanceOf(SQLException.class);
        assertThat(orders.find(order.orderId())).isEmpty();
    }
}
