package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PreDestroy;
import java.util.UUID;

/** Lives for one unit of work and reports to {@link UnitCloseLog} when that unit closes. */
@Component(scope = Scope.EVENT)
public class UnitProbeImpl implements UnitProbe {

    private final String id = UUID.randomUUID().toString();
    private final UnitCloseLog log;

    @Inject
    public UnitProbeImpl(UnitCloseLog log) {
        this.log = log;
    }

    @Override
    public String id() {
        return id;
    }

    @PreDestroy
    public void unitClosed() {
        log.closed(id);
    }
}
