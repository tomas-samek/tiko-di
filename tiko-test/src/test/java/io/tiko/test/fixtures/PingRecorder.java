package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records {@link Ping} events so a test can confirm an imperatively-published event arrived (#314). */
@Component(scope = Scope.SINGLETON)
public class PingRecorder {

    private final List<String> received = new CopyOnWriteArrayList<>();

    @EventHandler
    public void onPing(Ping ping) {
        received.add(ping.text());
    }

    public List<String> received() {
        return received;
    }
}
