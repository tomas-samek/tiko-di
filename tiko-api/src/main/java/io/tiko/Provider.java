package io.tiko;

/**
 * Provides instances of a specific type on demand.
 *
 * <p>Providers are useful for:</p>
 * <ul>
 *   <li>Breaking circular dependencies</li>
 *   <li>Lazy initialization</li>
 *   <li>Injecting EVENT-scoped beans into singleton beans</li>
 *   <li>Obtaining multiple instances of prototype-scoped beans</li>
 * </ul>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Component
 * @Singleton
 * public class UserService {
 *     private final Provider<RequestContext> contextProvider;
 *
 *     @Inject
 *     public UserService(Provider<RequestContext> contextProvider) {
 *         this.contextProvider = contextProvider;
 *     }
 *
 *     public void processRequest() {
 *         // Lazily resolve the current unit's EVENT-scoped bean
 *         RequestContext context = contextProvider.get();
 *         // Use context...
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of object provided
 */
@FunctionalInterface
public interface Provider<T> {

    /**
     * Provides an instance of type T.
     *
     * @return an instance of T
     */
    T get();
}
