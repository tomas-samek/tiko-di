package io.tiko.coroutines.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PreDestroy;
import java.util.UUID;

/** Lives for one unit of work and reports to {@link CloseLog} when that unit closes. */
@Component(scope = Scope.EVENT)
public class FreshRequestId implements RequestId {

    private final String value = UUID.randomUUID().toString();
    private final CloseLog log;

    @Inject
    public FreshRequestId(CloseLog log) {
        this.log = log;
    }

    @Override
    public String value() {
        return value;
    }

    @PreDestroy
    public void unitClosed() {
        log.closed(value);
    }
}
