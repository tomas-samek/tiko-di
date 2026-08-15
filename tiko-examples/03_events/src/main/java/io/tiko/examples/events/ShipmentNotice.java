package io.tiko.examples.events;

/** Notice emitted once per {@code @EventTrigger} on the fan-out stage. */
public record ShipmentNotice(String orderId, String customer) {}
