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
 * (so sync units, which the aggregator brackets, are not double-published). The detached unit is
 * open in every module, and the aggregator publishes its one
 * {@code EventStartedEvent}/{@code EventEndingEvent} pair (#811).
 */
@Component(scope = Scope.SINGLETON)
public class AsyncUserAuditor {

    @EventHandler(async = true)
    public void onUserCreated(UserCreatedEvent event) {
        // Body intentionally trivial — the test observes the unit's lifecycle bracket, not a
        // side effect. The detached EVENT scope opens and tears down around this invocation.
    }
}
