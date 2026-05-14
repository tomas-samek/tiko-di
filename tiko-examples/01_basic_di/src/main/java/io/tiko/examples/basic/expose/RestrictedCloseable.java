package io.tiko.examples.basic.expose;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton bean implementing both {@link Zeta} and {@link AutoCloseable}, but exposed
 * only via {@link Zeta}. Verifies that {@code @Component(expose = {…})} narrows
 * <em>injection routing</em> while the framework still recognises {@code AutoCloseable}
 * for implicit teardown at container shutdown. (Singleton scope is chosen here so the
 * direct impl is returned by {@code get()}; the orthogonal cross-scope proxy story is
 * exercised in the existing proxy tests.)
 */
@Component(
        scope = Scope.SINGLETON,
        expose = {Zeta.class})
public class RestrictedCloseable implements Zeta, AutoCloseable {

    /** Reset before each test; set when {@code close()} runs. */
    public static final AtomicBoolean CLOSED = new AtomicBoolean(false);

    @Override
    public void close() {
        CLOSED.set(true);
    }
}
