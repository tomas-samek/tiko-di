package io.tiko.examples.basic.crossscope;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A synchronous handler on an EVENT-scoped component. It exists only inside a unit of work, so an
 * event published outside one cannot be delivered to it; that failure goes to the
 * {@code ErrorHandler} like any other handler failure.
 */
@Component(scope = Scope.EVENT)
public class UnitListener {

    public static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    @EventHandler
    public void onPing(UnitPing ping) {
        RECEIVED.add(ping.text());
    }
}
