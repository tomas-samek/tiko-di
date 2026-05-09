package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to be invoked after the component has been constructed and all dependencies injected.
 *
 * <p>The annotated method must:</p>
 * <ul>
 *   <li>Have no parameters</li>
 *   <li>Return void</li>
 *   <li>Not be static</li>
 * </ul>
 *
 * <p><strong>Note:</strong> prefer <em>constructor injection</em> or a {@code @Produces}
 * factory method for initialization. With constructor injection, all dependencies are
 * already wired by the time the constructor body runs, so init logic belongs in the
 * constructor (or, for complex setup that needs validation, in a {@code @Produces}
 * factory). {@code @PostConstruct} is retained for compatibility and migration from
 * other frameworks; for greenfield code it is rarely the right choice.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Component
 * @Singleton
 * public class DatabaseConnection {
 *     @PostConstruct
 *     public void initialize() {
 *         // Connect to database
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostConstruct {
}
