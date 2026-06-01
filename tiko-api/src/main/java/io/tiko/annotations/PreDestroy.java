package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to be invoked before the component is destroyed by the container.
 *
 * <p>The annotated method must:</p>
 * <ul>
 *   <li>Have no parameters</li>
 *   <li>Return void</li>
 *   <li>Not be static</li>
 * </ul>
 *
 * <p>Invoked at scope teardown for scoped beans:</p>
 * <ul>
 *   <li>{@code SINGLETON}: during {@code container.shutdown()}.</li>
 *   <li>{@code EVENT}: at the end of {@code runInEventScope} /
 *       {@code supplyInEventScope}.</li>
 * </ul>
 *
 * <p>Hooks fire in reverse-creation (LIFO) order so a bean's dependencies are
 * still available during its cleanup. The corresponding {@code Ending} lifecycle
 * event ({@code ApplicationEndingEvent} / {@code EventEndingEvent}) is published
 * <em>before</em> any {@code @PreDestroy} runs. A failing hook is logged but does
 * not skip the rest of teardown.</p>
 *
 * <p>If the component implements {@link AutoCloseable} and declares no explicit
 * {@code @PreDestroy}, the container treats {@code close()} as an implicit
 * {@code @PreDestroy} hook — no annotation required. An explicit
 * {@code @PreDestroy} always wins to avoid double-cleanup.</p>
 *
 * <p><strong>Note:</strong> prefer implementing {@link AutoCloseable} for cleanup —
 * the container calls {@code close()} automatically and the JDK contract gives you a
 * single source of truth (one well-known method, no risk of accidentally declaring
 * multiple {@code @PreDestroy} methods). Use {@code @PreDestroy} only when the bean
 * needs a non-{@code close()} cleanup name, when implementing {@code AutoCloseable}
 * would be confusing for the bean's role, or for migration from other frameworks.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Component
 * @Singleton
 * public class DatabaseConnection {
 *     @PreDestroy
 *     public void cleanup() {
 *         // Close database connection
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreDestroy {}
