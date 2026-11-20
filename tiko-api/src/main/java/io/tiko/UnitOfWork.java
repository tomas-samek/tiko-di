package io.tiko;

/**
 * Opens units of work. A component that receives the inbound stimuli of an application itself,
 * such as an HTTP server registering its routes or a scheduler running its jobs, takes this
 * interface and runs each request or job in a unit of its own:
 *
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class OrderRoutes {
 *     private final UnitOfWork unitOfWork;
 *     private final OrderService orders;
 *
 *     @Inject
 *     public OrderRoutes(UnitOfWork unitOfWork, OrderService orders) {
 *         this.unitOfWork = unitOfWork;
 *         this.orders = orders;
 *     }
 *
 *     public Receipt place(OrderRequest request) {
 *         return unitOfWork.supplyInEventScope(() -> orders.place(request));
 *     }
 * }
 * }</pre>
 *
 * <p>It is a built-in dependency of a {@code @Component} constructor and of a {@code @Produces}
 * method, like {@link EventBus}. The {@link Container} is not injectable: a component that held it
 * could look anything up. A {@code Container} is a {@code UnitOfWork}, so code that has the
 * container, a {@code main} method or a test, can pass it wherever this interface is expected.
 *
 * <p>In an application of several modules the unit is the application's: the EVENT-scoped
 * components of every module live in it.
 */
public interface UnitOfWork {

    /**
     * Executes the given runnable within an EVENT scope (one unit of work). "Event scope" is the
     * unit of work, not a bus event: publishing an event opens none.
     * <p>
     * EVENT-scoped beans created during execution are destroyed when the scope exits
     * (LIFO {@code @PreDestroy}). EVENT is single-frame in {@code 0.x.0}: calling this
     * method while a unit is already open throws {@link IllegalStateException}.
     *
     * @param runnable the code to execute in event scope
     * @throws IllegalStateException if a unit of work is already open on the current thread
     */
    void runInEventScope(Runnable runnable);

    /**
     * Executes the given supplier within an EVENT scope (one unit of work) and returns its result.
     * <p>
     * EVENT-scoped beans created during execution are destroyed when the scope exits.
     * EVENT is single-frame in {@code 0.x.0}: calling this method while a unit is
     * already open throws {@link IllegalStateException}.
     *
     * @param supplier the code to execute in event scope
     * @param <T>      the return type
     * @return the result of the supplier
     * @throws IllegalStateException if a unit of work is already open on the current thread
     */
    <T> T supplyInEventScope(java.util.function.Supplier<T> supplier);
}
