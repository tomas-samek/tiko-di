package io.tiko.examples.basic.teardown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static destroy-order tracker shared by the teardown test fixtures. Tests reset
 * {@link #order} in {@code @BeforeEach}.
 *
 * <p>Not a {@code @Component} — it is just a static collection point used by
 * scoped-bean {@code @PreDestroy} methods to record what fired and in what order.
 */
public final class TeardownRecorder {

    public static final List<String> order = Collections.synchronizedList(new ArrayList<>());

    /** Captures the {@code RequestEndingEvent} timestamp index relative to {@link #order}. */
    public static final AtomicReference<Integer> requestEndingIndex = new AtomicReference<>();

    /** Captures the {@code EventEndingEvent} timestamp index relative to {@link #order}. */
    public static final AtomicReference<Integer> eventEndingIndex = new AtomicReference<>();

    public static void reset() {
        order.clear();
        requestEndingIndex.set(null);
        eventEndingIndex.set(null);
    }

    public static void record(String name) {
        order.add(name);
    }

    private TeardownRecorder() {}
}
