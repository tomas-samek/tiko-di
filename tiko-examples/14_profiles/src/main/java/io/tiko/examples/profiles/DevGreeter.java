package io.tiko.examples.profiles;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(
        scope = Scope.SINGLETON,
        profiles = {"dev"})
public final class DevGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "[dev] hi " + name + " — verbose dev greeting (debug build)";
    }
}
