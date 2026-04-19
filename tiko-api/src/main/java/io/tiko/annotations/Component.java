package io.tiko.annotations;

import io.tiko.Scope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a component that should be managed by the Tiko container.
 * The annotated class will be discovered at compile-time and registered for dependency injection.
 *
 * <p>Basic example:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class UserService {
 *     @Inject
 * public UserService(UserRepository repository) {
 *         this.repository = repository;
 *     }
 * }
 * }</pre>
 *
 * <p>With qualifier and profiles:</p>
 * <pre>{@code
 * @Component(
 *     scope = Scope.SINGLETON,
 *     name = "primary",
 *     profiles = {"prod", "staging"}
 * )
 * public class ProductionDatabase implements Database { }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Component {

    /**
     * The lifecycle scope of this component.
     * <p>
     * Determines when and how instances are created:
     * <ul>
     *   <li>{@link Scope#SINGLETON} - One instance per container</li>
     *   <li>{@link Scope#PROTOTYPE} - New instance per injection (default)</li>
     *   <li>{@link Scope#REQUEST} - One instance per request/event scope</li>
     * </ul>
     *
     * @return the component scope
     */
    Scope scope() default Scope.PROTOTYPE;

    /**
     * Optional qualifier name to distinguish between multiple implementations of the same type.
     * <p>
     * When specified, this component can be injected using {@code @Named("name")}.
     *
     * @return the qualifier name, or empty string for no qualifier
     */
    String name() default "";

    /**
     * Optional profiles that this component is active in.
     * <p>
     * If specified, the component is only available when one of these profiles is active.
     * Empty array (default) means the component is available in all profiles.
     *
     * @return array of profile names
     */
    String[] profiles() default {};
}
