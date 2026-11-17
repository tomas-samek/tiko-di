package io.tiko.examples.multimodule.modulea;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/** Module A's own note: one instance per unit of work. */
@Component(scope = Scope.EVENT)
public class LocalNote implements UnitNote {

    @Override
    public String origin() {
        return "module-a";
    }
}
