package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultEventExecutorFactoryTest {

    @Test
    void produces_threadpool_with_documented_settings() {
        ExecutorService es = DefaultEventExecutorFactory.create();
        try {
            assertThat(es).isInstanceOf(ThreadPoolExecutor.class);
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) es;

            int cores = Runtime.getRuntime().availableProcessors();
            assertThat(tpe.getCorePoolSize()).isEqualTo(Math.max(2, cores / 2));
            assertThat(tpe.getMaximumPoolSize()).isEqualTo(cores * 4);
            assertThat(tpe.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60);
            assertThat(tpe.getQueue()).isInstanceOf(LinkedBlockingQueue.class);
            assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(1024);
            // Shutdown-aware caller-runs (#346): backpressure while live, observable drop once stopping.
            assertThat(tpe.getRejectedExecutionHandler()).isInstanceOf(ShutdownAwareCallerRunsPolicy.class);
        } finally {
            es.shutdownNow();
        }
    }

    @Test
    void explicitSizingKnobsTakeEffectOnThePool() {
        ExecutorService es =
                DefaultEventExecutorFactory.create(256, OverflowPolicy.CALLER_RUNS, 3, 9, Duration.ofSeconds(30));
        try {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) es;
            assertThat(tpe.getCorePoolSize()).isEqualTo(3);
            assertThat(tpe.getMaximumPoolSize()).isEqualTo(9);
            assertThat(tpe.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(30);
            assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(256);
        } finally {
            es.shutdownNow();
        }
    }

    @Test
    void unsetSizingKnobsFallBackToProcessorDerivedDefaults() {
        // Sentinels (UNSET_POOL_SIZE / null) reproduce the historical processor-based sizing exactly.
        ExecutorService es = DefaultEventExecutorFactory.create(
                1024, OverflowPolicy.CALLER_RUNS, TikoOptions.UNSET_POOL_SIZE, TikoOptions.UNSET_POOL_SIZE, null);
        try {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) es;
            int cores = Runtime.getRuntime().availableProcessors();
            assertThat(tpe.getCorePoolSize()).isEqualTo(Math.max(2, cores / 2));
            assertThat(tpe.getMaximumPoolSize()).isEqualTo(cores * 4);
            assertThat(tpe.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60);
        } finally {
            es.shutdownNow();
        }
    }

    @Test
    void rejectsMaxPoolSizeBelowCoreSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        DefaultEventExecutorFactory.create(64, OverflowPolicy.CALLER_RUNS, 8, 4, Duration.ofSeconds(1)))
                .withMessageContaining("eventExecutorMaxSize");
    }

    @Test
    void threads_are_daemon_and_named_tiko_event_async() throws Exception {
        ExecutorService es = DefaultEventExecutorFactory.create();
        try {
            AtomicReference<Thread> captured = new AtomicReference<>();
            es.submit(() -> captured.set(Thread.currentThread())).get();

            Thread t = captured.get();
            assertThat(t.isDaemon()).isTrue();
            assertThat(t.getName()).startsWith("tiko-event-async-");
        } finally {
            es.shutdownNow();
        }
    }
}
