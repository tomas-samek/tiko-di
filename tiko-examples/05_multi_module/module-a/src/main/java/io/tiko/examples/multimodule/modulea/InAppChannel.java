package io.tiko.examples.multimodule.modulea;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/** Module A's own channel. */
@Component(scope = Scope.SINGLETON)
public class InAppChannel implements Channel {

    @Override
    public String name() {
        return "in-app";
    }
}
