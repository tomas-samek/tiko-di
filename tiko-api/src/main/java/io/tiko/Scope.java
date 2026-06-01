package io.tiko;

/**
 * Defines the lifecycle scope of a component.
 *
 * <p>Scopes form a hierarchy from longest to shortest lifetime:</p>
 * <ol>
 *   <li><strong>SINGLETON</strong> - Application lifetime</li>
 *   <li><strong>EVENT</strong> - One unit of work (HTTP request, consumed message,
 *       scheduled job, async dispatch); the generic unit-of-work primitive</li>
 *   <li><strong>PROTOTYPE</strong> - Per injection (shortest, default)</li>
 * </ol>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class UserService { }
 *
 * @Component(scope = Scope.EVENT)
 * public class TransactionContext { }
 *
 * @Component(scope = Scope.PROTOTYPE)
 * public class RequestBuilder { }
 * }</pre>
 *
 * <p>In {@code 0.x.0}, EVENT is single-frame — calling {@link Container#runInEventScope}
 * while a unit is already open throws {@link IllegalStateException}. Nestability is a
 * deferred-but-additive future change.
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
     * One instance per unit of work — the synchronous reach of an inbound stimulus
     * (HTTP request, consumed message, scheduled job, async dispatch), bounded at
     * every async/transport hop.
     * <p>
     * Instances are created on {@link Container#runInEventScope(Runnable)} entry and
     * destroyed on exit (LIFO {@code @PreDestroy}).
     * <p>
     * <strong>Lifetime:</strong> One unit of work
     * <p>
     * Note: EVENT-scoped beans injected into SINGLETON beans are automatically proxied
     * to resolve the current unit's instance. This requires the EVENT-scoped bean to
     * implement an interface.
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
