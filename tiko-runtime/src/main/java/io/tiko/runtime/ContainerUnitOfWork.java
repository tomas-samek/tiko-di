package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.Event;
import io.tiko.UnitOfWork;
import java.util.function.Supplier;

/**
 * The {@link UnitOfWork} a component is handed (#685). Generated code constructs one for each
 * {@code UnitOfWork} parameter:
 * <pre>{@code
 * UnitOfWork unitOfWork = new ContainerUnitOfWork(container, container.options());
 * }</pre>
 *
 * <p>It opens units and does nothing else: a component that holds it cannot reach the container
 * through it, which is why generated code does not pass the container itself.
 *
 * <p>A unit of work belongs to the whole application. Generated code runs inside one module's
 * container, so it also hands over that container's options: in a multi-module application they
 * name the container that aggregates the modules, and the unit is opened there, in every module.
 * A single-module container is the application, and opens the unit itself.
 */
public final class ContainerUnitOfWork implements UnitOfWork, UnitCarrier {

    private final Container container;
    private final TikoOptions options;

    /**
     * @param container the container the generated code runs in
     * @param options that container's options, or {@code null} for a unit of work over {@code container} alone
     */
    public ContainerUnitOfWork(Container container, TikoOptions options) {
        this.container = container;
        this.options = options;
    }

    /** Looked up on every call: the application's container builds its modules while it is being constructed. */
    private Container application() {
        var application = options == null ? null : options.applicationContainer();
        return application != null ? application : container;
    }

    @Override
    public void runInEventScope(Runnable runnable) {
        application().runInEventScope(runnable);
    }

    @Override
    public <T> T supplyInEventScope(Supplier<T> supplier) {
        return application().supplyInEventScope(supplier);
    }

    /** The application's unit, as the other two methods open it (#677). */
    @Override
    public CarriedUnit openCarriedUnit(Event<?> origin) {
        return CarriedUnit.openIn(application(), origin);
    }
}
