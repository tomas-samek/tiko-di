package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test fixture for #47 lifecycle assertions. Singletons can't be created and torn
 * down repeatedly inside one JVM via container reuse, so the counters are static
 * and tests reset them in {@code @BeforeEach}.
 *
 * <p>Lives in the main source set so the annotation processor includes it in the
 * generated container; only the test source set reads the static counters.
 */
@Component(scope = Scope.SINGLETON)
public class ShutdownTestCounter {

    public static final AtomicInteger postConstructCount = new AtomicInteger(0);
    public static final AtomicInteger preDestroyCount = new AtomicInteger(0);

    /** Set by tests that want to verify get() during @PreDestroy works (bypass marker). */
    public static final AtomicReference<Container> CONTAINER_REF = new AtomicReference<>();

    public static final AtomicReference<Object> RETRIEVED_DURING_PREDESTROY = new AtomicReference<>();
    public static final AtomicReference<Throwable> ERROR_DURING_PREDESTROY = new AtomicReference<>();

    public static void reset() {
        postConstructCount.set(0);
        preDestroyCount.set(0);
        CONTAINER_REF.set(null);
        RETRIEVED_DURING_PREDESTROY.set(null);
        ERROR_DURING_PREDESTROY.set(null);
    }

    @PostConstruct
    public void onCreate() {
        postConstructCount.incrementAndGet();
    }

    @PreDestroy
    public void onDestroy() {
        preDestroyCount.incrementAndGet();
        Container c = CONTAINER_REF.get();
        if (c != null) {
            try {
                RETRIEVED_DURING_PREDESTROY.set(c.get(ShutdownTestCounter.class));
            } catch (Throwable t) {
                ERROR_DURING_PREDESTROY.set(t);
            }
        }
    }
}
