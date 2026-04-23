package io.tiko.examples.basic;

public final class Timestamp {

    private final long value;

    private Timestamp(long value) {
        this.value = value;
    }

    public long getValue() {
        return value;
    }

    public static Timestamp of(long value) {
        return new Timestamp(value);
    }
}
