package io.tiko.examples.persistence.batch;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import io.tiko.examples.persistence.infra.TransactionalScope;
import io.tiko.examples.persistence.repo.OrderRepository;
import io.tiko.runtime.Tiko;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Batch entry point: processes N orders inside one unit of work (one EVENT
 * scope, one transaction). All N inserts commit together or all roll back
 * together — all-or-none semantics.
 *
 * <p>Per-iteration observability (e.g. {@link BatchAuditLogger#record(UUID)})
 * is invoked directly from the loop body — under the single-frame EVENT
 * model, the batch is one scope, not N. Genuinely independent events that
 * need to be retryable or distributed each own their own unit and txn;
 * cross-event consistency is a saga / outbox concern above the DI layer.
 *
 * <p>Run with {@code java -cp <jar> io.tiko.examples.persistence.batch.BatchEntry}
 * (the shaded jar's default main class is {@code HttpEntry}).
 */
public final class BatchEntry {

    private BatchEntry() {}

    public static void main(String[] args) {
        Container container = Tiko.create(ConfigSources.classpath("application.yml"));
        try {
            int processed = processBatch(container, sampleFixture());
            System.out.println("[batch] committed " + processed + " orders");
        } finally {
            container.shutdown();
        }
    }

    /**
     * Process a batch of orders inside one unit of work / one transaction.
     * Returns the number successfully committed (always {@code orders.size()}
     * on success, since the helper throws on poison records).
     */
    public static int processBatch(Container container, List<Order> orders) {
        return TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            var audit = container.get(BatchAuditLogger.class);
            for (Order o : orders) {
                try {
                    repo.insert(o);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                audit.record(o.id());
            }
            return orders.size();
        });
    }

    private static List<Order> sampleFixture() {
        return List.of(
                new Order(UUID.randomUUID(), "alice", "NEW", Instant.now(), List.of(new OrderItem(1, "sku-1", 2))),
                new Order(
                        UUID.randomUUID(),
                        "bob",
                        "NEW",
                        Instant.now(),
                        List.of(new OrderItem(1, "sku-2", 1), new OrderItem(2, "sku-3", 4))),
                new Order(UUID.randomUUID(), "carol", "NEW", Instant.now(), List.of(new OrderItem(1, "sku-4", 7))));
    }
}
