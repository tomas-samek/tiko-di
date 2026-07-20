package io.tiko.examples.asyncscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * #435: a single-module container must honor the {@link TikoOptions} pool-sizing knobs
 * ({@code eventExecutorCoreSize} / {@code eventExecutorMaxSize} / {@code eventExecutorKeepAlive},
 * #110). They were silently dropped because the generated constructor called the two-arg
 * {@code DefaultEventExecutorFactory.create(queueCapacity, overflowPolicy)} overload, which
 * substitutes the processor-derived defaults for core/max and a default keep-alive.
 *
 * <p>The bounds are read straight off the framework-owned {@link ThreadPoolExecutor} returned by
 * {@link Container#getEventExecutor()} — a direct, race-free view of what the knobs configured.
 */
class PoolSizingKnobsTest {

    @Test
    void explicitKnobsProduceExactlyThoseBounds() {
        var options = TikoOptions.builder()
                .eventExecutorCoreSize(1)
                .eventExecutorMaxSize(3)
                .eventExecutorKeepAlive(Duration.ofSeconds(7))
                .build();
        try (Container container = Tiko.create(options)) {
            var pool = (ThreadPoolExecutor) container.getEventExecutor();
            assertThat(pool.getCorePoolSize()).isEqualTo(1);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
            assertThat(pool.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(7L);
        }
    }

    @Test
    void omittingKnobsReproducesProcessorDerivedDefaults() {
        // Mirrors DefaultEventExecutorFactory's UNSET_POOL_SIZE fallbacks: core = max(2, cores/2),
        // max = cores * 4, keep-alive = 60s. Omitting the knobs must leave these untouched.
        int cores = Runtime.getRuntime().availableProcessors();
        try (Container container = Tiko.create(TikoOptions.builder().build())) {
            var pool = (ThreadPoolExecutor) container.getEventExecutor();
            assertThat(pool.getCorePoolSize()).isEqualTo(Math.max(2, cores / 2));
            assertThat(pool.getMaximumPoolSize()).isEqualTo(cores * 4);
            assertThat(pool.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60L);
        }
    }
}
