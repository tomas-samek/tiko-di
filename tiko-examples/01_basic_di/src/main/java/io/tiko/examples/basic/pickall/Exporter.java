package io.tiko.examples.basic.pickall;

/** A format a report can be exported in. Its implementations are unnamed: only a picker asks for them. */
public interface Exporter {
    String format();
}
