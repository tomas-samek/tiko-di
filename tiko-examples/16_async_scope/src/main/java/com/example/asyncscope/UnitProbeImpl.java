package com.example.asyncscope;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import java.util.UUID;

@Component(scope = Scope.EVENT)
public class UnitProbeImpl implements UnitProbe {
    private final String id = UUID.randomUUID().toString();

    @Override
    public String id() {
        return id;
    }

    @PostConstruct
    public void created() {
        ProbeLog.created(id);
    }

    @PreDestroy
    public void destroyed() {
        ProbeLog.destroyed(id);
    }
}
