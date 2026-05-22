package io.tiko.test;

import io.tiko.Scope;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-classpath marker that shadows a production {@code @Component} of the same target type.
 *
 * <p>The {@code tiko-processor} processes {@code @TestComponent}-annotated classes during
 * {@code test-compile} and emits a separate {@code TestTikoContainerImpl_<hash>} into
 * {@code target/test-classes/}. At runtime, {@link io.tiko.runtime.Tiko#create(io.tiko.runtime.TikoOptions)}
 * prefers the test container when {@code META-INF/tiko/test-container.properties} is on the classpath.
 *
 * <p>Shadow resolution:
 * <ul>
 *   <li>When {@link #value()} is set, the annotated class shadows that explicit target. The annotated
 *       class must be assignable to the value type.</li>
 *   <li>When {@code value()} is unset (default {@link Void Void.class}), the processor walks the
 *       superclass chain and shadows the first {@code @Component}-annotated ancestor (if any).</li>
 *   <li>When neither produces a target, the class is a pure addition with no shadowing.</li>
 * </ul>
 *
 * <p>{@code SOURCE} retention — never appears in runtime bytecode.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TestComponent {

    /**
     * Explicit shadow target. When set, the test component shadows the production
     * {@code @Component} that resolves to this type. The annotated class MUST be
     * assignable to {@code value()} (compile-time check).
     *
     * <p>When unset (default {@link Void Void.class}), the processor walks the
     * test class's superclass chain and shadows the first {@code @Component}-annotated
     * ancestor (if any).
     */
    Class<?> value() default Void.class;

    Scope scope() default Scope.SINGLETON;

    String name() default "";
}
