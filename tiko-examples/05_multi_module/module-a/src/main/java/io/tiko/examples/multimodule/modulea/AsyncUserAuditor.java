package io.tiko.examples.multimodule.modulea;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;

/**
 * Async cross-frame handler used to prove that async {@code @EventHandler} dispatch under an
 * {@code AggregatingContainer} opens its own EVENT unit and publishes that unit's lifecycle
 * events (#433).
 *
 * <p>Async dispatch runs the handler in a detached EVENT scope on the shared executor. The
 * per-module container is constructed by the aggregator with {@code publishLifecycleEvents=false}
 * (so sync units, which the aggregator brackets, are not double-published) — yet the aggregator
 * is <em>not</em> on the async dispatch path, so the module container must be the sole publisher
 * of the detached unit's {@code EventStartedEvent}/{@code EventEndingEvent} pair.
 */
@Component(scope = Scope.SINGLETON)
public class AsyncUserAuditor {

    @EventHandler(async = true)
    public void onUserCreated(UserCreatedEvent event) {
        // Body intentionally trivial — the test observes the unit's lifecycle bracket, not a
        // side effect. The detached EVENT scope opens and tears down around this invocation.
    }
}
