package io.tiko.examples.multimodule.moduleb;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.examples.multimodule.modulea.UnitNote;

/** The note Module B adds to Module A's {@link UnitNote} interface: one instance per unit of work. */
@Component(scope = Scope.EVENT)
public class RemoteNote implements UnitNote {

    @Override
    public String origin() {
        return "module-b";
    }
}
