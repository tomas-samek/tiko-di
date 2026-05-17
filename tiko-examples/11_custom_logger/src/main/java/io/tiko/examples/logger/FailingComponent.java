package io.tiko.examples.logger;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/**
 * Has a {@code @PreDestroy} that throws — exercises {@code DefaultErrorHandler}'s
 * log path during container teardown, producing a framework WARNING that flows
 * through slf4j → logback.
 */
@Component(scope = Scope.SINGLETON)
public class FailingComponent {

    @PreDestroy
    public void cleanup() {
        throw new IllegalStateException("simulated teardown failure");
    }
}
