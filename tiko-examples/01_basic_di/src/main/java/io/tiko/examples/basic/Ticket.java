package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component(scope = Scope.PROTOTYPE)
public class Ticket {
    private static final AtomicLong COUNTER = new AtomicLong();
    private final long id = COUNTER.incrementAndGet();

    public long getId() {
        return id;
    }
}
