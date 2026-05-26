package io.tiko.examples.basic.ordering;

/** Async user event used to assert that async dispatch detaches from the triggering scope (#167). */
public record AsyncPing() {}
