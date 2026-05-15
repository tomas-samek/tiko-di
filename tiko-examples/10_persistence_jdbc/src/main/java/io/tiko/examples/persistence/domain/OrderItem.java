package io.tiko.examples.persistence.domain;

/** One line of a purchase order. */
public record OrderItem(int lineNo, String sku, int qty) {}
