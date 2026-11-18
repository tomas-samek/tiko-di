package io.tiko.examples.multimodule.modulea;

/** A note that belongs to one unit of work; each module adds its own EVENT-scoped implementation. */
public interface UnitNote {
    String origin();

    /** Identifies this instance: two reads in one unit of work see the same id. */
    String id();
}
