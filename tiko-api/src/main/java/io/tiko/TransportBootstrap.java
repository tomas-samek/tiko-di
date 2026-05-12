package io.tiko;

/**
 * Discovered by {@code ServiceLoader<TransportBootstrap>} at container startup. Every
 * transport module (`tiko-kafka`, future `tiko-http`, `tiko-scheduler`, ...) emits one
 * implementation of this interface via its own annotation processor and registers it
 * through {@code META-INF/services/io.tiko.TransportBootstrap}.
 *
 * <p>Lifecycle, in order:
 * <ol>
 *   <li>{@code Tiko.create(...)} builds the container and calls {@link Container#start()}.</li>
 *   <li>For every discovered {@link TransportBootstrap}, the runtime invokes
 *       {@link #start(Container)} with the live, fully-instantiated container. By this
 *       point all singleton {@code @Component}s exist, the {@code EventBus} is wired,
 *       and bound {@code @Configuration} records are injectable. Transports use
 *       {@link Container#get(Class)} to resolve their bridge components.</li>
 *   <li>On {@link Container#shutdown()}, the runtime invokes {@link #shutdown()} on every
 *       transport <em>before</em> the container runs its own {@code @PreDestroy} LIFO
 *       chain. Bridge {@code @Component}s are still alive while the transport releases
 *       its resources (closes consumers/producers, joins threads).</li>
 * </ol>
 *
 * <p>Implementations must be idempotent: a second {@code start()} or {@code shutdown()}
 * call has no effect.
 */
public interface TransportBootstrap {

    /**
     * Wire transport-specific subscriptions / launch consumer threads. Called once
     * after {@code container.start()} returns.
     */
    void start(Container container);

    /**
     * Release transport-owned resources. Called once during {@code container.shutdown()},
     * before the container runs its own {@code @PreDestroy} chain.
     */
    void shutdown();
}
