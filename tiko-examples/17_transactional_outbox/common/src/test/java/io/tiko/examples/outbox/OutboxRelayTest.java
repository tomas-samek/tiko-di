package io.tiko.examples.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The relay drains a backlog at broker speed, keeps a row whose send fails for a later tick, and sends
 * nothing while the outbox is empty. It polls every 250 ms, so a 50-row backlog drained one row per poll
 * would take 12.5 s; the deadline below is far shorter.
 */
class OutboxRelayTest {

    private static final int BACKLOG = 50;

    private DataSource database;
    private MockProducer<String, byte[]> producer;

    @BeforeEach
    void openOutbox() throws SQLException {
        database = Database.open("jdbc:h2:mem:outbox-relay-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        Database.initialize(database, JdbcOutbox.SCHEMA);
        producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
    }

    @Test
    void backlogDrainsInOrderWellWithinOnePollPerRow() throws SQLException {
        var keys = IntStream.range(0, BACKLOG).mapToObj(i -> "order-" + i).toList();
        enqueue(keys);

        try (var relay = new OutboxRelay(database, producer)) {
            await().atMost(Duration.ofSeconds(2)).until(() -> pendingRows() == 0);
        }

        assertThat(sentKeys()).containsExactlyElementsOf(keys);
    }

    @Test
    void failedSendKeepsTheRowAndNothingBehindItIsSentFirst() throws SQLException {
        var keys = List.of("first", "second", "third");
        enqueue(keys);
        producer.sendException = new KafkaException("broker unavailable");

        try (var relay = new OutboxRelay(database, producer)) {
            await().pollDelay(Duration.ofMillis(600))
                    .atMost(Duration.ofSeconds(2))
                    .until(() -> true);
            assertThat(producer.history()).isEmpty();
            assertThat(pendingRows()).isEqualTo(keys.size());

            producer.sendException = null;
            await().atMost(Duration.ofSeconds(2)).until(() -> pendingRows() == 0);
        }

        assertThat(sentKeys()).containsExactlyElementsOf(keys);
    }

    @Test
    void emptyOutboxSendsNothing() {
        try (var relay = new OutboxRelay(database, producer)) {
            await().pollDelay(Duration.ofMillis(600))
                    .atMost(Duration.ofSeconds(2))
                    .until(() -> true);
        }

        assertThat(producer.history()).isEmpty();
    }

    private void enqueue(List<String> keys) throws SQLException {
        Database.transaction(database, transaction -> {
            for (var key : keys) {
                JdbcOutbox.enqueue(transaction, Messages.ORDERS, key, key);
            }
            return null;
        });
    }

    private List<String> sentKeys() {
        return producer.history().stream().map(ProducerRecord::key).toList();
    }

    private long pendingRows() throws SQLException {
        try (var connection = database.getConnection();
                var count = connection.createStatement();
                var rows = count.executeQuery("SELECT COUNT(*) FROM outbox")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
