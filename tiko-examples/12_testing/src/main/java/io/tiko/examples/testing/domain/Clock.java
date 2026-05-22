package io.tiko.examples.testing.domain;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.time.Instant;

@Component(scope = Scope.SINGLETON)
public class Clock {
    public Instant now() {
        return Instant.now();
    }
}
