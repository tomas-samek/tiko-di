package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component(scope = Scope.SINGLETON)
public class AuditRecorder {

    public final List<AuditRecorded> received = new CopyOnWriteArrayList<>();

    @EventHandler
    public void on(AuditRecorded e) {
        received.add(e);
    }
}
