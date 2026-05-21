package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON)
public class SimpleService {
    public String hello() {
        return "world";
    }
}
