package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.Tiko;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for #47: concurrent {@code get()} on thread A racing
 * {@code shutdown()} on thread B should never leave a constructed-but-not-destroyed
 * singleton.
 *
 * <p>The drain barrier in {@code shutdown()} waits for in-flight {@code get()} calls
 * to complete before iterating {@code @PreDestroy}, so any successful get() must
 * have its singleton's {@code @PreDestroy} run.
 */
class ConcurrentGetShutdownTest {

    @RepeatedTest(20)
    void no_constructed_but_not_destroyed_singletons_under_race() throws Exception {
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

        // Property: either the getter succeeded (and PreDestroy ran on the constructed
        // singleton) or the getter was rejected with ISE (and shutdown found nothing to
        // destroy). Never "constructed but PreDestroy skipped."
        if (getterSucceeded.get()) {
            assertThat(ShutdownTestCounter.preDestroyCount.get())
                .as("getter succeeded → singleton was constructed → @PreDestroy must have run")
                .isEqualTo(1);
        } else {
            assertThat(getterRejected)
                .as("getter must either succeed or be cleanly rejected with ISE")
                .isTrue();
            assertThat(ShutdownTestCounter.preDestroyCount.get())
                .as("getter rejected → singleton never constructed → @PreDestroy never invoked")
                .isEqualTo(0);
        }
    }
}
