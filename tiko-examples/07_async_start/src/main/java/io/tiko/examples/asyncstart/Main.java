package io.tiko.examples.asyncstart;

import io.tiko.Container;
import io.tiko.runtime.Tiko;

/**
 * Demonstrates that {@code @EventHandler(async = true)} on {@code ApplicationStartedEvent}
 * keeps heavy startup work off the critical path — {@code Tiko.create()} returns
 * to the caller while the warmup is still running on a background executor thread.
 *
 * <p>Sample output (timings vary; the punchline is that the second timestamp is small,
 * the third is roughly {@code WORK_MS} later):
 *
 * <pre>{@code
 * [T=   0ms] calling Tiko.create()...
 * [T=  18ms] Tiko.create() returned -- main thread is free to do work
 * [T= 519ms] async ApplicationStartedEvent handler finished its 500ms warmup
 * [T= 519ms] container closed
 * }</pre>
 *
 * <p>Run with:
 *
 * <pre>{@code
 * mvn -pl tiko-examples/07_async_start -am exec:java \
 *     -Dexec.mainClass=io.tiko.examples.asyncstart.Main
 * }</pre>
 */
public final class Main {

    public static void main(String[] args) throws InterruptedException {
        long t0 = System.nanoTime();
        System.out.printf("[T=%4dms] calling Tiko.create()...%n", 0L);

        try (Container container = Tiko.create()) {
            long createReturned = ms(System.nanoTime() - t0);
            System.out.printf("[T=%4dms] Tiko.create() returned -- main thread is free to do work%n", createReturned);

            // Real apps would be doing useful work here (serving requests, scheduling
            // tasks, accepting connections). The async warmup keeps running on the
            // event executor and does not interfere.
            SlowStartupHook.awaitDone();

            long handlerDone = ms(System.nanoTime() - t0);
            System.out.printf(
                    "[T=%4dms] async ApplicationStartedEvent handler finished its %dms warmup%n",
                    handlerDone, SlowStartupHook.WORK_MS);
        }
        long shutdown = ms(System.nanoTime() - t0);
        System.out.printf("[T=%4dms] container closed%n", shutdown);
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000L;
    }

    private Main() {}
}
