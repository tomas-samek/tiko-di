package io.tiko.examples.asyncscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * #438: a single-module container must report {@link Container#eventExecutorMetrics()} for the
 * framework-owned event pool, matching {@code AggregatingContainer} — the generated
 * {@code TikoContainerImpl} previously emitted no override and inherited the {@code empty()}
 * default, so single-module apps saw no pool metrics while multi-module apps did.
 */
class EventExecutorMetricsTest {

    @Test
    void frameworkOwnedPoolReportsMetrics() {
        var options = TikoOptions.builder()
                .eventExecutorCoreSize(2)
                .eventExecutorMaxSize(5)
                .build();
        try (Container container = Tiko.create(options)) {
            assertThat(container.eventExecutorMetrics())
                    .as("framework-owned ThreadPoolExecutor must be sampleable")
                    .isPresent()
                    .get()
                    .satisfies(metrics -> {
                        assertThat(metrics.corePoolSize()).isEqualTo(2);
                        assertThat(metrics.maxPoolSize()).isEqualTo(5);
                        assertThat(metrics.queueRemainingCapacity()).isGreaterThanOrEqualTo(0);
                    });
        }
    }

    @Test
    void userSuppliedExecutorReportsNoMetrics() {
        ExecutorService userExecutor = Executors.newFixedThreadPool(2);
        var options = TikoOptions.builder().eventExecutor(userExecutor).build();
        try (Container container = Tiko.create(options)) {
            assertThat(container.eventExecutorMetrics())
                    .as("a user-supplied executor is the user's to observe — the container reports empty")
                    .isEmpty();
        } finally {
            userExecutor.shutdownNow();
        }
    }
}
