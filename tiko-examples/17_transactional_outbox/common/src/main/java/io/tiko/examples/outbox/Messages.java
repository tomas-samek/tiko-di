package io.tiko.examples.outbox;

import java.util.Objects;
import java.util.UUID;

public final class Messages {
    public static final String ORDERS = "outbox-orders";
    public static final String RESULTS = "outbox-results";

    private Messages() {}

    public record OrderPlaced(UUID orderId, String sku, int quantity) {
        public OrderPlaced {
            Objects.requireNonNull(orderId, "orderId");
            if (sku == null || !sku.matches("[a-zA-Z0-9_-]{1,40}") || quantity < 1) {
                throw new IllegalArgumentException("A SKU and a positive quantity are required");
            }
        }
    }

    public enum Decision {
        RESERVED,
        REJECTED
    }

    public record ReservationDecided(UUID orderId, Decision decision) {
        public ReservationDecided {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(decision, "decision");
        }
    }
}
