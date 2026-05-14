package io.tiko.examples.basic.expose;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.ApplicationStartedEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uninjectable side-effect bean: SINGLETON with {@code exposeSelf = false}.
 * Eagerly constructed at container start; its {@code @EventHandler} method
 * still fires on {@code ApplicationStartedEvent} despite the bean being
 * unreachable via {@code container.get(...)}.
 */
@Component(
        scope = Scope.SINGLETON,
        exposeSelf = false)
public class HiddenEventListener {

    /** Reset before each test; set when the event handler fires. */
    public static final AtomicBoolean FIRED = new AtomicBoolean(false);

    @EventHandler
    public void onAppStart(ApplicationStartedEvent event) {
        FIRED.set(true);
    }
}
