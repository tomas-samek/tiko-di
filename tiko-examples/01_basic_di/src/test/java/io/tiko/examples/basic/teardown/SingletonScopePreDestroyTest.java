package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the SINGLETON {@code @PreDestroy} contract: shutdown destroys components in
 * reverse-dependency order (LIFO). A dependent is destroyed before the dep it relies
 * on, so a service that flushes via its repository in {@code @PreDestroy} sees a live
 * repo instance.
 *
 * <p>Mirrors {@code RequestScopePreDestroyTest#destroy_order_is_reverse_creation_lifo}
 * for the SINGLETON scope.
 */
class SingletonScopePreDestroyTest {

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void destroy_order_is_reverse_dependency_lifo() {
        Container container = Tiko.create();
        try {
            // Touching A pulls in B which pulls in C → creation chain: C, B, A.
            container.get(LifoSingletonA.class);
        } finally {
            container.shutdown();
        }

        // LIFO: dependent destroyed before dep. A → B → C.
        var singletonEntries = TeardownRecorder.order.stream()
                .filter(s -> s.startsWith("Singleton"))
                .toList();
        assertThat(singletonEntries).containsExactly("SingletonA", "SingletonB", "SingletonC");
    }
}
