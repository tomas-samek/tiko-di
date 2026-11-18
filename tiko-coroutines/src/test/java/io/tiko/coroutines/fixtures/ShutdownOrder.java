package io.tiko.coroutines.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.PreDestroy;
import io.tiko.events.ApplicationEndingEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Writes down what happens while the container closes, in the order it happens. */
@Component(scope = Scope.SINGLETON)
public class ShutdownOrder {

    private final List<String> seen = new CopyOnWriteArrayList<>();

    public void note(String what) {
        seen.add(what);
    }

    public List<String> seen() {
        return List.copyOf(seen);
    }

    @EventHandler
    public void onEnding(ApplicationEndingEvent ending) {
        seen.add("application ending");
    }

    @PreDestroy
    public void destroyed() {
        seen.add("singleton destroyed");
    }
}
