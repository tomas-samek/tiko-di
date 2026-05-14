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
 *
 * <p><strong>Hidden event handler (uninjectable side-effect bean).</strong> Combine
 * {@code expose = {}} with {@code exposeSelf = false} on a SINGLETON to declare a bean
 * that exists solely to react to events — constructed eagerly at container start, its
 * {@code @EventHandler} methods participate normally, but nothing else can inject it:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON, expose = {}, exposeSelf = false)
 * public class StartupBookkeeper {
 *     @EventHandler
 *     public void onAppStart(ApplicationStartedEvent e) {
 *         // side-effect-only work — metrics, telemetry, etc.
 *     }
 * }
 * }</pre>
 * <p>Event-handler dispatch is independent of {@link #expose()} /
 * {@link #exposeSelf()} — those control the public {@code container.get(Class)} routing,
 * not framework wiring. Use SINGLETON scope: REQUEST/EVENT-scoped hidden beans would
 * only exist while their scope is active, and PROTOTYPE has no canonical instance to
 * subscribe.</p>
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

    /**
     * Optional allowlist narrowing which types this component is injectable as.
     * <p>
     * When <strong>empty (the default)</strong>, the component behaves permissively: it is
     * registered under every interface it implements (and under itself if {@link #exposeSelf()}
     * is {@code true}). Existing code that does not declare {@code expose} keeps today's
     * Spring-style behavior — every implemented interface is routable.
     * <p>
     * When <strong>non-empty</strong>, only the listed types are routable for injection /
     * {@code container.get(Class)}. Marker or side-effect interfaces such as
     * {@link AutoCloseable} that the framework dispatches on (e.g. for implicit teardown)
     * remain recognised regardless of this list — see the class-level note on the
     * separation between <em>injection routing</em> and <em>framework dispatch</em>.
     * <p>
     * Every type listed here must be implemented by the annotated class (or be the class
     * itself). The annotation processor validates this at compile time.
     *
     * <pre>{@code
     * @Component(scope = Scope.SINGLETON, expose = { NotificationApi.class })
     * public class EmailNotificationService
     *         implements NotificationApi, AutoCloseable, MetricsSource {
     *     // Routable via NotificationApi only.
     *     // AutoCloseable is still recognised for implicit teardown.
     *     // MetricsSource is implementation-internal — no @Inject MetricsSource gets this bean.
     * }
     * }</pre>
     *
     * @return the explicit allowlist of types to expose; empty for the permissive default
     */
    Class<?>[] expose() default {};

    /**
     * Whether the concrete implementation class itself is routable via
     * {@code container.get(MyClass.class)}.
     * <p>
     * Defaults to {@code true} (today's behavior). Set to {@code false} to hide the impl
     * class so only the declared interfaces — either the permissive default set or an
     * explicit {@link #expose()} list — name this bean.
     *
     * @return {@code true} if the impl class is routable; {@code false} for interface-only
     */
    boolean exposeSelf() default true;
}
