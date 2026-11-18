package io.tiko.coroutines.fixtures;

import io.tiko.Scope;
import io.tiko.UnitOfWork;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

/**
 * What a Ktor route module or a scheduler is to the application: a SINGLETON that takes the
 * built-in {@link UnitOfWork} and reaches the current unit's {@link RequestId} through its proxy.
 */
@Component(scope = Scope.SINGLETON)
public class Requests {

    private final UnitOfWork unitOfWork;
    private final RequestId requestId;

    @Inject
    public Requests(UnitOfWork unitOfWork, RequestId requestId) {
        this.unitOfWork = unitOfWork;
        this.requestId = requestId;
    }

    public UnitOfWork unitOfWork() {
        return unitOfWork;
    }

    /** The id of the unit of work the caller is in. */
    public String currentId() {
        return requestId.value();
    }
}
