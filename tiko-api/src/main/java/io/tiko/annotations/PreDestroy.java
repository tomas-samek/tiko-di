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
 * <p>For Singleton beans, this is called during container shutdown.
 * For RequestScoped beans, this is called at the end of the request scope.</p>
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
public @interface PreDestroy {
}
