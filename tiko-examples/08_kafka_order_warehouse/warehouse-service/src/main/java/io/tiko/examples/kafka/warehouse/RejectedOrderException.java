package io.tiko.examples.kafka.warehouse;

/**
 * An order the warehouse can never fulfil, however often it is redelivered. {@link WarehouseIngestPolicy}
 * dead-letters it instead of retrying, so it doesn't block the records behind it on its partition.
 */
public class RejectedOrderException extends RuntimeException {

    public RejectedOrderException(String orderId, String reason) {
        super("Order " + orderId + " rejected: " + reason);
    }
}
