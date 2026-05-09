package io.tiko.examples.multimodule.app;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
    void all_per_module_containers_share_the_same_executor_instance() throws Exception {
        ExecutorService custom = Executors.newSingleThreadExecutor();
        try {
            TikoOptions opts = TikoOptions.builder().eventExecutor(custom).build();
            try (Container aggregator = Tiko.create(opts)) {
                // Reflectively walk the aggregator's per-module containers and assert
                // each reports the same executor instance.
                var perModule = perModuleContainers(aggregator);
                assertThat(perModule)
                    .as("multi-module setup must have at least 2 per-module containers")
                    .hasSizeGreaterThanOrEqualTo(2);
                for (Container module : perModule) {
                    assertThat(module.getEventExecutor())
                        .as("per-module container's executor must be the shared aggregator executor")
                        .isSameAs(custom);
                }
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
