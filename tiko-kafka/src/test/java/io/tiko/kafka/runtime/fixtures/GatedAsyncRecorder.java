package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Async handler gated on a latch so a test can observe the world while the handler has
 * been dispatched but not yet executed (#341 — offset commit precedes async handler
 * execution). Only {@code KafkaHandlerCommitSemanticsTest} publishes {@link GatedPing},
 * so the closed gate never blocks other tests.
 */
@Component(scope = Scope.SINGLETON)
public class GatedAsyncRecorder {

    public final CountDownLatch gate = new CountDownLatch(1);
    public final List<GatedPing> received = new CopyOnWriteArrayList<>();

    @EventHandler(async = true)
    public void on(GatedPing event) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) {
                return; // test never released the gate — drop rather than hang teardown
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        received.add(event);
    }
}
