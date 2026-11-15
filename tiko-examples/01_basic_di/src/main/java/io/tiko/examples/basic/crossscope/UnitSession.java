package io.tiko.examples.basic.crossscope;

/** A value that belongs to one unit of work; each unit gets its own, numbered in creation order. */
public interface UnitSession {
    int number();
}
