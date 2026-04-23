package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON, name = "spanish")
public class SpanishGreeter implements Greeter {
    @Override
    public String greet() {
        return "Hola";
    }
}
