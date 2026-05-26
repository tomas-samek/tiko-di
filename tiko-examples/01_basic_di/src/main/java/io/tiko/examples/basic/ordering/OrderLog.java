package io.tiko.examples.basic.ordering;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ordered log shared by the lifecycle-ordering fixtures (#167). One instance per container, so
 * each test gets a fresh log automatically. Thread-safe because an async handler writes to it
 * from an executor thread while the test thread reads.
 */
@Component(scope = Scope.SINGLETON)
public class OrderLog {

    private final List<String> entries = new CopyOnWriteArrayList<>();

    public void record(String entry) {
        entries.add(entry);
    }

    public List<String> snapshot() {
        return List.copyOf(entries);
    }
}
