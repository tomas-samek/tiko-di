package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.UnitOfWork;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.util.function.Function;

/**
 * What an HTTP server or a scheduler does (#685): it takes the built-in {@link UnitOfWork} and
 * runs each piece of inbound work in a unit of its own, without holding the container.
 */
@Component(scope = Scope.SINGLETON)
public class UnitRunner {

    private final UnitOfWork unitOfWork;
    private final UnitProbe probe;

    @Inject
    public UnitRunner(UnitOfWork unitOfWork, UnitProbe probe) {
        this.unitOfWork = unitOfWork;
        this.probe = probe;
    }

    /** Runs {@code work} in a new unit, handing it that unit's probe. */
    public <T> T inUnit(Function<UnitProbe, T> work) {
        return unitOfWork.supplyInEventScope(() -> work.apply(probe));
    }

    /** As {@link #inUnit}, through {@code runInEventScope}. */
    public void runInUnit(Runnable work) {
        unitOfWork.runInEventScope(work);
    }
}
