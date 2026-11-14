package io.tiko.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
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
 * <p>A {@link TikoTestOptions} method adds to the options the container boots with, such as a
 * config source or a replaced transport.
 *
 * <p>Use {@link #lifecycle()} {@link Lifecycle#PER_CLASS} to share one container across
 * every {@code @Test} method on the class (boot in {@code @BeforeAll}, teardown in
 * {@code @AfterAll}).
 *
 * <p>The annotation is inherited: a subclass uses the lifecycle its superclass states, and a
 * {@code @Nested} class the one of its enclosing class, unless it carries {@code @TikoTest}
 * itself. Under {@link Lifecycle#PER_CLASS} a {@code @Nested} class is a test class of its own
 * and gets its own container.
 */
@Inherited
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
