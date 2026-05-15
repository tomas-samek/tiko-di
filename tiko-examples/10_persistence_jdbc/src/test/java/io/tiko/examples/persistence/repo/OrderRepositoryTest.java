package io.tiko.examples.persistence.repo;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import io.tiko.examples.persistence.infra.TransactionalScope;
import io.tiko.runtime.Tiko;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link OrderRepository} against a real H2 in-memory DB.
 * Cross-connection verification ensures the row is committed, not
 * just visible to the inserting transaction.
 */
class OrderRepositoryTest {

    private Container container;

    @BeforeEach
    void setUp() {
        container = Tiko.create(ConfigSources.classpath("application.yml"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (container != null) container.shutdown();
        try (Connection c =
                        DriverManager.getConnection("jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
                Statement st = c.createStatement()) {
            st.execute("DELETE FROM order_items");
            st.execute("DELETE FROM orders");
        }
    }

    @Test
    void insertedOrderIsVisibleViaFindById() {
        UUID id = UUID.randomUUID();
        Order toInsert = new Order(
                id, "alice", "NEW", Instant.now(), List.of(new OrderItem(1, "sku-1", 2), new OrderItem(2, "sku-2", 3)));

        TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            try {
                repo.insert(toInsert);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        Order found = TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            try {
                return repo.findById(id).orElseThrow();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.customer()).isEqualTo("alice");
        assertThat(found.items()).hasSize(2);
        assertThat(found.items()).extracting(OrderItem::sku).containsExactly("sku-1", "sku-2");
    }

    @Test
    void findByIdReturnsEmptyForUnknownOrder() {
        var result = TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            try {
                return repo.findById(UUID.randomUUID());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(result).isEmpty();
    }

    @Test
    void insertedRowIsCommittedNotJustVisibleInSession() throws Exception {
        UUID id = UUID.randomUUID();
        Order toInsert = new Order(id, "bob", "NEW", Instant.now(), List.of(new OrderItem(1, "sku-x", 1)));

        TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            try {
                repo.insert(toInsert);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        try (Connection c =
                        DriverManager.getConnection("jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT customer FROM orders WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("bob");
        }
    }
}
