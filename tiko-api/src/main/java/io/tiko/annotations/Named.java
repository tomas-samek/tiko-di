package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifier annotation used to disambiguate between multiple implementations of the same type.
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Component
 * @Named("primary")
 * public class PrimaryDatabase implements Database { }
 *
 * @Component
 * @Named("cache")
 * public class CacheDatabase implements Database { }
 *
 * @Component
 * public class UserService {
 *     @Inject
 *     public UserService(@Named("primary") Database database) {
 *         this.database = database;
 *     }
 * }
 * }</pre>
 */
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.SOURCE)
public @interface Named {
    /**
     * The name used to distinguish this component from others of the same type.
     *
     * @return the qualifier name
     */
    String value();
}
