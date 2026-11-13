package io.tiko.test.fixtures;

import io.tiko.Container;
import io.tiko.TransportBootstrap;
import java.util.concurrent.CountDownLatch;

/**
 * A replacement transport whose consumer thread, started through {@code container.threadFactory()}
 * like a Kafka consumer's, does not stop on shutdown: the leak a {@code @TikoTest} must report.
 */
public class StuckConsumerTransport implements TransportBootstrap {

    private static volatile CountDownLatch release = new CountDownLatch(1);
    private static volatile Thread consumer;

    @Override
    public void start(Container container) {
        var latch = release;
        var thread = container.threadFactory().newThread(() -> awaitIgnoringInterrupts(latch));
        thread.setName("stuck-consumer");
        consumer = thread;
        thread.start();
    }

    @Override
    public void shutdown() {
        // the consumer ignores shutdown on purpose
    }

    /** The consumer thread of the last start, or {@code null}. */
    public static Thread consumer() {
        return consumer;
    }

    /** Lets the last consumer exit and arms a fresh latch for the next start. */
    public static void releaseAndReset() {
        release.countDown();
        release = new CountDownLatch(1);
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        var interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
