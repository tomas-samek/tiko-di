package io.tiko.examples.multimodule.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.examples.multimodule.modulea.NoteDesk;
import io.tiko.runtime.ContainerWork;
import io.tiko.runtime.OverflowPolicy;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #678: the work another Tiko module runs for a container, its coroutines, belongs to the
 * application, not to one module. In a multi-module application it is counted and stopped once,
 * by the application's {@code close()}, while the modules still answer lookups.
 */
class MultiModuleContainerWorkTest {

    /** Stands in for the coroutine scope: notes when the application stops it and what still works then. */
    private static final class StoppedOnce implements ContainerWork.Stoppable {
        private final Container container;
        final List<String> stops = new ArrayList<>();

        StoppedOnce(Container container) {
            this.container = container;
        }

        @Override
        public void stop(long deadlineNanos) {
            stops.add("stopped while lookups work: " + (container.get(NoteDesk.class) != null));
        }
    }

    @Test
    void theApplicationCountsAndStopsTheWorkOnce() {
        StoppedOnce scope;
        int pendingWhileRetained;
        try (Container container = Tiko.create()) {
            var work = ContainerWork.of(container.getEventExecutor());
            scope = work.stoppedAtClose(StoppedOnce.class, () -> new StoppedOnce(container))
                    .orElseThrow();
            var release = work.retainDelivery();
            pendingWhileRetained = container.pendingAsyncDeliveries();
            release.run();
        }

        assertThat(pendingWhileRetained)
                .as("counted with the application's deliveries")
                .isEqualTo(1);
        assertThat(scope.stops).containsExactly("stopped while lookups work: true");
    }

    /** #679: the limit and the policy that work is held to are the application's options. */
    @Test
    void theWorkIsHeldToTheApplicationsOverflowOptions() {
        var options = TikoOptions.builder()
                .queueCapacity(7)
                .onOverflow(OverflowPolicy.DROP)
                .build();
        try (Container container = Tiko.create(options)) {
            var work = ContainerWork.of(container.getEventExecutor());

            assertThat(work.queueCapacity()).isEqualTo(7);
            assertThat(work.onOverflow()).isEqualTo(OverflowPolicy.DROP);
            assertThat(work.isOwnThread()).as("the test's thread").isFalse();
        }
    }
}
