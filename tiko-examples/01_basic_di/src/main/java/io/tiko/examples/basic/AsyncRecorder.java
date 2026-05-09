package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test fixture for async event integration tests. Records the thread name
 * and counts down a latch so the test can wait for completion.
 */
@Component(scope = Scope.SINGLETON)
public class AsyncRecorder {

    public static final AtomicReference<String> threadName = new AtomicReference<>();
    public static volatile CountDownLatch latch = new CountDownLatch(1);

    @EventHandler(async = true)
    public void onPing(AsyncPing event) {
        threadName.set(Thread.currentThread().getName());
        latch.countDown();
    }
}
