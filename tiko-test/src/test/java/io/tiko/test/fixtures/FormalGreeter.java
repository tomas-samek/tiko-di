package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON, name = "formal")
public class FormalGreeter implements Greeter {
    @Override
    public String greet() {
        return "Good day";
    }
}
