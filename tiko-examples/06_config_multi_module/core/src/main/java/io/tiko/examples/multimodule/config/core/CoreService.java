package io.tiko.examples.multimodule.config.core;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

@Component(scope = Scope.SINGLETON)
public class CoreService {

    private final CoreConfig config;

    @Inject
    public CoreService(CoreConfig config) {
        this.config = config;
    }

    public CoreConfig config() {
        return config;
    }
}
