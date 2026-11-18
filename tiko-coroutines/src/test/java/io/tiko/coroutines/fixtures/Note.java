package io.tiko.coroutines.fixtures;

/** An event the tests publish from inside a unit of work. */
public record Note(String text) {}
