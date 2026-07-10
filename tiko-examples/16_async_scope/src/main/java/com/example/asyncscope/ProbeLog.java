package com.example.asyncscope;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Static capture for probe lifecycle; reset per test to keep state from bleeding. */
public final class ProbeLog {
    private static final Queue<String> CREATED = new ConcurrentLinkedQueue<>();
    private static final Queue<String> DESTROYED = new ConcurrentLinkedQueue<>();

    private ProbeLog() {}

    static void created(String id) {
        CREATED.add(id);
    }

    static void destroyed(String id) {
        DESTROYED.add(id);
    }

    public static java.util.List<String> createdIds() {
        return java.util.List.copyOf(CREATED);
    }

    public static java.util.List<String> destroyedIds() {
        return java.util.List.copyOf(DESTROYED);
    }

    public static void reset() {
        CREATED.clear();
        DESTROYED.clear();
    }
}
