package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.Tiko;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for #47: concurrent {@code get()} on thread A racing
 * {@code shutdown()} on thread B should never deadlock, hang, or throw an
 * unexpected exception. The getter either succeeds (returning the singleton)
 * or is cleanly rejected with {@link IllegalStateException} per the post-shutdown
 * gate. {@code @PreDestroy} runs exactly once regardless of the race winner.
 *
 * <p>Single-module {@code Tiko.create()} eagerly constructs all SINGLETON
 * components during {@code start()}, so the lazy-construction race the issue body
 * describes ("getter constructs a new singleton during shutdown") doesn't surface
 * here — it requires multi-module + lazy paths. What this test covers is the
 * cleaner subset: the drain barrier and gate behave correctly when get() and
 * shutdown() execute concurrently.
 */
class ConcurrentGetShutdownTest {

    @RepeatedTest(20)
    void shutdown_and_get_coordinate_cleanly_under_race() throws Exception {
        ShutdownTestCounter.reset();

        Container container = Tiko.create();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean getterSucceeded = new AtomicBoolean(false);
        AtomicBoolean getterRejected = new AtomicBoolean(false);

        Thread getter = new Thread(() -> {
            try {
                start.await();
                container.get(ShutdownTestCounter.class);
                getterSucceeded.set(true);
            } catch (IllegalStateException expected) {
                // shutdown() raced ahead; this is the documented post-shutdown behaviour
                getterRejected.set(true);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "concurrent-getter");

        Thread shutter = new Thread(() -> {
            try {
                start.await();
                container.shutdown();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "concurrent-shutter");

        getter.start();
        shutter.start();
        start.countDown();

        getter.join(2000);
        shutter.join(2000);

        assertThat(getterSucceeded.get() || getterRejected.get())
            .as("getter must complete with either success or a clean ISE — never hang or throw something else")
            .isTrue();
        assertThat(ShutdownTestCounter.preDestroyCount.get())
            .as("singleton was eagerly constructed in start(); shutdown runs @PreDestroy exactly once")
            .isEqualTo(1);
    }
}
