package io.tiko.examples.persistence.batch;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import io.tiko.runtime.Tiko;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BatchEntryIntegrationTest {

    private static final String JDBC_URL = "jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Container container;

    @BeforeEach
    void setUp() {
        container = Tiko.create(ConfigSources.classpath("application.yml"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (container != null) container.shutdown();
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement()) {
            st.execute("DELETE FROM order_items");
            st.execute("DELETE FROM orders");
        }
    }

    @Test
    void batchCommitsAllOrdersAndAuditLoggerCapturedEach() throws Exception {
        List<Order> orders = makeOrders(5);

        int committed = BatchEntry.processBatch(container, orders);
        assertThat(committed).isEqualTo(5);

        // Cross-connection check: all 5 orders + their items are in the DB.
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(5);
        }

        // Auto-proxy demonstration: audit logger saw EVENT-scoped CurrentOrder
        // resolve to a different order id on each of the 5 iterations.
        List<UUID> seen = container.get(BatchAuditLogger.class).captured();
        assertThat(seen).hasSize(5);
        assertThat(seen)
                .containsExactlyElementsOf(orders.stream().map(Order::id).toList());
    }

    private List<Order> makeOrders(int n) {
        var out = new ArrayList<Order>();
        for (int i = 0; i < n; i++) {
            out.add(new Order(
                    UUID.randomUUID(),
                    "customer-" + i,
                    "NEW",
                    Instant.now(),
                    List.of(new OrderItem(1, "sku-" + i, 1))));
        }
        return out;
    }
}
