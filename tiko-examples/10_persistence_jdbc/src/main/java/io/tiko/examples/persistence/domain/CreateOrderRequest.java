package io.tiko.examples.persistence.domain;

import java.util.List;

/** JSON body for POST /orders. */
public record CreateOrderRequest(String customer, List<OrderItem> items) {}
