package io.tiko.examples.basic.ordering;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;

/**
 * A plain SINGLETON whose {@code @PostConstruct} must complete before
 * {@code ApplicationStartedEvent} fires — {@code start()} eagerly initialises all singletons,
 * then publishes the event (#167).
 */
@Component(scope = Scope.SINGLETON)
public class EagerBean {

    private final OrderLog log;

    @Inject
    public EagerBean(OrderLog log) {
        this.log = log;
    }

    @PostConstruct
    public void init() {
        log.record("PC:Eager");
    }
}
