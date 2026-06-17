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
        AggregatingContainer container =
                new AggregatingContainer(new LocalEventBus(), NOOP_ERROR_HANDLER, /* userEventExecutor= */ null);
        try {
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
        } finally {
            container.shutdown();
        }
    }

    @Test
    void userSuppliedExecutorYieldsEmptyMetrics() {
        // A user-supplied executor — even a ThreadPoolExecutor — is the user's to observe.
        ThreadPoolExecutor userPool = (ThreadPoolExecutor) java.util.concurrent.Executors.newFixedThreadPool(2);
        AggregatingContainer container = new AggregatingContainer(new LocalEventBus(), NOOP_ERROR_HANDLER, userPool);
        try {
            assertThat(container.eventExecutorMetrics()).isEmpty();
        } finally {
            container.shutdown();
            userPool.shutdownNow();
        }
    }
}
