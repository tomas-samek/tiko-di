package io.tiko.kafka.runtime.integration.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component(scope = Scope.SINGLETON)
public class ItOrderRecorder {

    public final List<ItOrderPlaced> received = new CopyOnWriteArrayList<>();

    @EventHandler
    public void on(ItOrderPlaced event) {
        received.add(event);
    }
}
