package io.tiko.examples.multimodule.moduleb;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.examples.multimodule.modulea.Channel;

/** The channel Module B adds to Module A's {@link Channel} interface. */
@Component(scope = Scope.SINGLETON)
public class EmailChannel implements Channel {

    @Override
    public String name() {
        return "email";
    }
}
