package io.tiko;

/**
 * Defines the lifecycle scope of a component.
 *
 * <p>Scopes form a hierarchy from longest to shortest lifetime:</p>
 * <ol>
 *   <li><strong>SINGLETON</strong> - Application lifetime</li>
 *   <li><strong>REQUEST</strong> - Logical unit of work (HTTP request, batch job, transaction)</li>
 *   <li><strong>EVENT</strong> - Single event processing (one handler execution)</li>
 *   <li><strong>PROTOTYPE</strong> - Per injection (shortest)</li>
 * </ol>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class UserService { }
 *
 * @Component(scope = Scope.REQUEST)
 * public class TransactionContext { }
 *
 * @Component(scope = Scope.EVENT)
 * public class EventContext { }
 *
 * @Component(scope = Scope.PROTOTYPE)
 * public class RequestBuilder { }
 * }</pre>
 */
public enum Scope {
    /**
     * Single instance per container, shared across the entire application.
     * <p>
     * Recommended for stateless services, repositories, and DAOs.
     * Created lazily on first access and destroyed during container shutdown.
     * <p>
     * <strong>Lifetime:</strong> Application
     */
    SINGLETON,

    /**
     * One instance per request scope.
     * <p>
     * Represents a coarse-grained logical unit of work such as:
     * <ul>
     *   <li>HTTP request processing</li>
     *   <li>Batch job execution</li>
     *   <li>Transaction boundaries</li>
     * </ul>
     * A single REQUEST scope may process multiple EVENTs.
     * <p>
     * Instances are created when entering a request scope via
     * {@code container.runInRequestScope()} and destroyed when the scope exits.
     * <p>
     * <strong>Lifetime:</strong> Request/Transaction/Batch
     * <p>
     * Note: REQUEST-scoped beans injected into SINGLETON beans will be
     * automatically proxied to resolve the current request's instance.
     * This requires the REQUEST-scoped bean to implement an interface.
     */
    REQUEST,

    /**
     * One instance per event processing scope.
     * <p>
     * Represents a fine-grained unit of work for a single event handler execution.
     * Perfect for event-specific context like event ID, timestamp, or correlation data.
     * <p>
     * Multiple events can be processed within a single REQUEST scope, but each
     * event gets its own EVENT scope.
     * <p>
     * Instances are created when entering an event scope via
     * {@code container.runInEventScope()} and destroyed when the scope exits.
     * <p>
     * <strong>Lifetime:</strong> Single event processing
     * <p>
     * Note: EVENT-scoped beans injected into SINGLETON or REQUEST beans will be
     * automatically proxied. This requires the EVENT-scoped bean to implement an interface.
     */
    EVENT,

    /**
     * New instance created for each injection point.
     * <p>
     * Default scope if not specified. Recommended for stateful objects,
     * builders, and DTOs. No caching is performed - every injection gets
     * a brand new instance.
     * <p>
     * <strong>Lifetime:</strong> Per injection (shortest)
     */
    PROTOTYPE
}
