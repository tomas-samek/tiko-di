package com.example.asyncscope;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Component(scope = Scope.SINGLETON)
public class AsyncProbeHandlers {

    public record Touch() {}

    public record FlakyTouch() {}

    public record BlockedTouch() {}

    public record SlowTouch() {}

    public static final Queue<String> TOUCHED_IDS = new ConcurrentLinkedQueue<>();
    public static final Queue<String> TOUCHED_THREADS = new ConcurrentLinkedQueue<>();
    public static final AtomicInteger FLAKY_ATTEMPTS = new AtomicInteger();
    public static volatile CountDownLatch blockGate = new CountDownLatch(0);

    private final UnitProbe probe;

    public AsyncProbeHandlers(UnitProbe probe) {
        this.probe = probe; // EVENT-in-SINGLETON: interface-backed proxy, resolves the current unit
    }

    @EventHandler(async = true)
    public void onTouch(Touch event) {
        TOUCHED_IDS.add(probe.id());
        TOUCHED_THREADS.add(Thread.currentThread().getName());
    }

    /**
     * Worker occupier for the CALLER_RUNS test: waits on the gate with a 2s ceiling, so no
     * interleaving can deadlock — even if this handler itself gets caller-run inline on the
     * publishing thread, it self-releases.
     */
    @EventHandler(async = true)
    public void onSlowTouch(SlowTouch event) {
        TOUCHED_IDS.add(probe.id());
        TOUCHED_THREADS.add(Thread.currentThread().getName());
        try {
            blockGate.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @EventHandler(async = true, retries = 2)
    public void onFlakyTouch(FlakyTouch event) {
        TOUCHED_IDS.add(probe.id());
        if (FLAKY_ATTEMPTS.incrementAndGet() < 3) {
            throw new IllegalStateException("flaky attempt " + FLAKY_ATTEMPTS.get());
        }
    }

    @EventHandler(async = true, timeout = "PT0.2S")
    public void onBlockedTouch(BlockedTouch event) {
        TOUCHED_IDS.add(probe.id());
        try {
            blockGate.await(); // interrupted on timeout breach -> unit teardown
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e); // async @EventHandler methods cannot declare checked
            // exceptions — no dispatch call site in the codebase does — so re-throw unchecked.
        }
    }

    public static void reset() {
        TOUCHED_IDS.clear();
        TOUCHED_THREADS.clear();
        FLAKY_ATTEMPTS.set(0);
        blockGate = new CountDownLatch(0);
    }
}
