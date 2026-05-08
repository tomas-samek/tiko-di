package io.tiko.examples.multimodule.app;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.Tiko;
import io.tiko.TikoOptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #51 regression pin: {@code TikoOptions.errorHandler(...)} is propagated through the
 * aggregator to per-module containers. errorHandler propagation was already working
 * after #45 — this test pins the behaviour against future regression alongside the
 * new executor propagation.
 *
 * <p>Note: programmatic-subscriber failures are caught by {@code LocalEventBus.publish}
 * directly (defense-in-depth path) and do <em>not</em> route to the user's
 * {@code ErrorHandler}; that path is reserved for {@code @EventHandler}-registered
 * dispatchers with rich {@code EventHandlerInfo}. So this test verifies that the
 * user-supplied handler is at least <em>installed</em> by checking the per-module
 * containers' {@code getErrorHandler()} value reflectively.
 */
class MultiModuleErrorHandlerPropagationTest {

    @Test
    void user_supplied_error_handler_reaches_per_module_containers() throws Exception {
        AtomicReference<Object> recorder = new AtomicReference<>();
        ErrorHandler custom = ctx -> recorder.set(ctx);

        TikoOptions opts = TikoOptions.builder().errorHandler(custom).build();
        try (Container aggregator = Tiko.create(opts)) {
            var perModule = perModuleContainers(aggregator);
            assertThat(perModule).hasSizeGreaterThanOrEqualTo(2);

            for (Container module : perModule) {
                ErrorHandler installed = readErrorHandler(module);
                assertThat(installed)
                    .as("per-module container's errorHandler must be the user-supplied instance")
                    .isSameAs(custom);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Container> perModuleContainers(Container aggregator) throws Exception {
        var field = aggregator.getClass().getDeclaredField("moduleContainers");
        field.setAccessible(true);
        return (java.util.List<Container>) field.get(aggregator);
    }

    private static ErrorHandler readErrorHandler(Container module) throws Exception {
        var method = module.getClass().getMethod("getErrorHandler");
        return (ErrorHandler) method.invoke(module);
    }
}
