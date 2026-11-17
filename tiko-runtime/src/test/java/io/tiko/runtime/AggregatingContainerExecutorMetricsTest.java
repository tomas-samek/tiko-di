package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ErrorHandler;
import io.tiko.ExecutorMetrics;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code Container.eventExecutorMetrics()} (#110): the framework-owned default pool is
 * sampleable and the snapshot mirrors the live {@link ThreadPoolExecutor} getters, while a
 * user-supplied executor reports empty (their lifecycle, their metrics).
 *
 * <p>Resolves to StubContainer via {@code src/test/resources/META-INF/tiko/container.properties}.
 */
class AggregatingContainerExecutorMetricsTest {

    private static final ErrorHandler NOOP_ERROR_HANDLER = ctx -> {};

    @Test
    void frameworkOwnedPoolExposesSnapshotMatchingLiveGetters() {
        try (AggregatingContainer container =
                new AggregatingContainer(new LocalEventBus(), NOOP_ERROR_HANDLER, /* userEventExecutor= */ null)) {
            Optional<ExecutorMetrics> metrics = container.eventExecutorMetrics();
            assertThat(metrics).isPresent();

            // Cross-check the snapshot against the live executor's getters (acceptance: accurate).
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) container.getEventExecutor();
            ExecutorMetrics m = metrics.orElseThrow();
            assertThat(m.corePoolSize()).isEqualTo(tpe.getCorePoolSize());
            assertThat(m.maxPoolSize()).isEqualTo(tpe.getMaximumPoolSize());
            assertThat(m.queueRemainingCapacity()).isEqualTo(tpe.getQueue().remainingCapacity());
            assertThat(m.activeCount()).isEqualTo(tpe.getActiveCount());
            assertThat(m.queueSize()).isEqualTo(tpe.getQueue().size());
        }
    }

    /** #761: the container reports what is pending, and its metrics carry the same number. */
    @Test
    void pendingDeliveriesAreReportedByTheContainerAndInItsMetrics() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (AggregatingContainer container =
                new AggregatingContainer(new LocalEventBus(), NOOP_ERROR_HANDLER, /* userEventExecutor= */ null)) {
            assertThat(container.pendingAsyncDeliveries()).isZero();
            container.getEventExecutor().execute(() -> {
                started.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            assertThat(container.pendingAsyncDeliveries()).isEqualTo(1);
            assertThat(container.eventExecutorMetrics().orElseThrow().pendingDeliveries())
                    .isEqualTo(1);
            gate.countDown();
        }
    }

    /** The handle {@code Tiko.create(...)} returns passes the question on to its container. */
    @Test
    void bootHandleDelegatesPendingDeliveriesToItsContainer() {
        var container = new BootstrapTestContainer() {
            @Override
            public int pendingAsyncDeliveries() {
                return 7;
            }
        };
        io.tiko.TransportBootstrap transport = new io.tiko.TransportBootstrap() {
            @Override
            public void start(io.tiko.Container started) {
                // nothing to start
            }

            @Override
            public void shutdown() {
                // nothing to release
            }
        };

        assertThat(Tiko.startTransports(container, java.util.List.of(transport)).pendingAsyncDeliveries())
                .isEqualTo(7);
        assertThat(new BootstrapTestContainer().pendingAsyncDeliveries())
                .as("a container that does not count reports none")
                .isZero();
    }

    @Test
    void userSuppliedExecutorYieldsEmptyMetrics() {
        // A user-supplied executor — even a ThreadPoolExecutor — is the user's to observe.
        try (ThreadPoolExecutor userPool = (ThreadPoolExecutor) java.util.concurrent.Executors.newFixedThreadPool(2);
                AggregatingContainer container =
                        new AggregatingContainer(new LocalEventBus(), NOOP_ERROR_HANDLER, userPool)) {
            assertThat(container.eventExecutorMetrics()).isEmpty();
        }
    }
}
