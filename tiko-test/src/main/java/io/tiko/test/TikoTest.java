package io.tiko.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a JUnit 5 test class as a Tiko-managed test.
 *
 * <p>The {@link TikoTestExtension} is registered automatically: it boots a fresh
 * {@link io.tiko.Container} around each test by default ({@link Lifecycle#PER_METHOD}),
 * installs a {@link RecordingEventBus} via {@link io.tiko.runtime.TikoOptions#eventBusDecorator},
 * and resolves {@code @Test} / lifecycle method parameters out of the container.
 *
 * <p>Special parameter types are resolved from the extension store rather than the container:
 * {@link io.tiko.Container}, {@link io.tiko.EventBus}, and {@link RecordingEventBus}. Every
 * other parameter type is looked up via {@code container.get(type[, name])} — annotate with
 * {@link io.tiko.annotations.Named} to disambiguate.
 *
 * <p>Use {@link #lifecycle()} {@link Lifecycle#PER_CLASS} to share one container across
 * every {@code @Test} method on the class (boot in {@code @BeforeAll}, teardown in
 * {@code @AfterAll}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(TikoTestExtension.class)
public @interface TikoTest {

    /**
     * Container lifecycle for this test class. Defaults to {@link Lifecycle#PER_METHOD}.
     */
    Lifecycle lifecycle() default Lifecycle.PER_METHOD;

    /** Container lifecycle scope for a {@link TikoTest}-annotated class. */
    enum Lifecycle {
        /** Fresh container per {@code @Test} method (default). Safest, slowest. */
        PER_METHOD,

        /** One container shared across every {@code @Test} method on the class. */
        PER_CLASS
    }
}
