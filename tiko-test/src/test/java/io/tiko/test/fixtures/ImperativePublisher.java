package io.tiko.test.fixtures;

import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

/**
 * The #314 ergonomic path: a {@code @Component} that publishes imperatively by taking the
 * built-in {@link EventBus} as a constructor dependency — no hand-constructed plain class.
 */
@Component(scope = Scope.SINGLETON)
public class ImperativePublisher {

    private final EventBus eventBus;

    @Inject
    public ImperativePublisher(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void fire(String text) {
        eventBus.publish(new Ping(text));
    }
}
