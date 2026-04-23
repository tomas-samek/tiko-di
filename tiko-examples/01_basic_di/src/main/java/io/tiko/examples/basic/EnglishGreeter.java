package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON, name = "english")
public class EnglishGreeter implements Greeter {
    @Override
    public String greet() {
        return "Hello";
    }
}
