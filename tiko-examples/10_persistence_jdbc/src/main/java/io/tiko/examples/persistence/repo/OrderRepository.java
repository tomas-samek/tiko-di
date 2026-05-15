package io.tiko.examples.persistence.repo;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SINGLETON repository operating on a REQUEST-scoped {@link Connection}.
 * The {@code connection} field looks like a captured-at-construction
 * object, but it is a Tiko-generated auto-proxy: every method call
 * resolves to the current REQUEST scope's connection. Calling the
 * repository outside an active REQUEST scope fails with a scope error.
 */
@Component(scope = Scope.SINGLETON)
public class OrderRepository {

    private static final String INSERT_ORDER =
            "INSERT INTO orders (id, customer, status, created_at) VALUES (?, ?, ?, ?)";
    private static final String INSERT_ITEM =
            "INSERT INTO order_items (order_id, line_no, sku, qty) VALUES (?, ?, ?, ?)";
    private static final String SELECT_ORDER = "SELECT id, customer, status, created_at FROM orders WHERE id = ?";
    private static final String SELECT_ITEMS =
            "SELECT line_no, sku, qty FROM order_items WHERE order_id = ? ORDER BY line_no";

    private final Connection connection;

    @Inject
    public OrderRepository(Connection connection) {
        this.connection = connection;
    }

    public void insertHeader(Order order) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_ORDER)) {
            ps.setObject(1, order.id());
            ps.setString(2, order.customer());
            ps.setString(3, order.status());
            ps.setTimestamp(4, Timestamp.from(order.createdAt()));
            ps.executeUpdate();
        }
    }

    public void insertItem(UUID orderId, OrderItem item) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_ITEM)) {
            ps.setObject(1, orderId);
            ps.setInt(2, item.lineNo());
            ps.setString(3, item.sku());
            ps.setInt(4, item.qty());
            ps.executeUpdate();
        }
    }

    /** Convenience: insert header + all items. Used by tests + batch entry. */
    public void insert(Order order) throws SQLException {
        insertHeader(order);
        for (OrderItem item : order.items()) {
            insertItem(order.id(), item);
        }
    }

    public Optional<Order> findById(UUID id) throws SQLException {
        Order base;
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ORDER)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                base = new Order(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getTimestamp(4).toInstant(),
                        List.of());
            }
        }
        List<OrderItem> items = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ITEMS)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new OrderItem(rs.getInt(1), rs.getString(2), rs.getInt(3)));
                }
            }
        }
        return Optional.of(new Order(base.id(), base.customer(), base.status(), base.createdAt(), items));
    }
}
