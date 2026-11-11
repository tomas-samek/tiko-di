package io.tiko.examples.multimodule.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * #51 verification: {@code TikoOptions.eventExecutor(...)} is honoured in multi-module
 * setups. Before #51, the aggregator silently dropped the user-supplied executor and
 * each per-module container created its own default — N independent thread pools.
 */
class MultiModuleExecutorPropagationTest {

    @Test
    void user_supplied_executor_is_returned_by_get_event_executor() {
        ExecutorService custom = Executors.newSingleThreadExecutor();
        try {
            TikoOptions opts = TikoOptions.builder().eventExecutor(custom).build();
            try (Container container = Tiko.create(opts)) {
                assertThat(container.getEventExecutor())
                        .as("aggregator must return the user-supplied executor")
                        .isSameAs(custom);
            }
        } finally {
            custom.shutdownNow();
        }
    }

    @Test
    void all_per_module_containers_share_one_executor_running_on_the_supplied_one() throws Exception {
        ExecutorService custom = Executors.newSingleThreadExecutor();
        try {
            Thread customThread = custom.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            TikoOptions opts = TikoOptions.builder().eventExecutor(custom).build();
            try (Container aggregator = Tiko.create(opts)) {
                // Reflectively walk the aggregator's per-module containers: they share one executor
                // (#51), which dispatches onto the supplied one. It is a view of it that also
                // schedules async retries on the container's retry thread (#653).
                var perModule = perModuleContainers(aggregator);
                assertThat(perModule)
                        .as("multi-module setup must have at least 2 per-module containers")
                        .hasSizeGreaterThanOrEqualTo(2);
                ExecutorService shared = perModule.get(0).getEventExecutor();
                for (Container module : perModule) {
                    assertThat(module.getEventExecutor())
                            .as("per-module containers must share one executor")
                            .isSameAs(shared);
                }
                assertThat(shared.submit(Thread::currentThread).get(5, TimeUnit.SECONDS))
                        .as("the shared executor runs tasks on the user-supplied executor")
                        .isSameAs(customThread);
            }
        } finally {
            custom.shutdownNow();
        }
    }

    @Test
    void user_supplied_executor_is_not_shut_down_by_container_shutdown() {
        ExecutorService custom = Executors.newSingleThreadExecutor();
        try {
            TikoOptions opts = TikoOptions.builder().eventExecutor(custom).build();
            Container container = Tiko.create(opts);
            container.shutdown();

            assertThat(custom.isShutdown())
                    .as("user-supplied executor must NOT be shut down — user owns lifecycle")
                    .isFalse();
        } finally {
            custom.shutdownNow();
        }
    }

    @Test
    void framework_owned_executor_is_shut_down_by_container_shutdown() throws Exception {
        Container container = Tiko.create();
        ExecutorService frameworkOwned = container.getEventExecutor();
        assertThat(frameworkOwned.isShutdown()).isFalse();

        container.shutdown();

        // 10s drain is documented; allow generous slack
        boolean terminated = frameworkOwned.awaitTermination(11, TimeUnit.SECONDS);
        assertThat(terminated)
                .as("framework-owned executor must be shut down (and terminated) by container.shutdown()")
                .isTrue();
        assertThat(frameworkOwned.isShutdown()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Container> perModuleContainers(Container aggregator) throws Exception {
        var field = aggregator.getClass().getDeclaredField("moduleContainers");
        field.setAccessible(true);
        return (java.util.List<Container>) field.get(aggregator);
    }
}
