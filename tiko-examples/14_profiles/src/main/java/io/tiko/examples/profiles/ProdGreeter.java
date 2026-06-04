package io.tiko.examples.profiles;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(
        scope = Scope.SINGLETON,
        profiles = {"prod"})
public final class ProdGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "Hello, " + name + ".";
    }
}
