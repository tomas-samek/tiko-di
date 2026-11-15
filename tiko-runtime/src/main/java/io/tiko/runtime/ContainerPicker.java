package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.NoSuchComponentException;
import io.tiko.Picker;
import java.util.List;
import java.util.Optional;

/**
 * Generic {@link Picker} implementation backed by a {@link Container}. One class
 * services every {@code Picker<T>} injection point — no per-type codegen needed,
 * because the type information lives in the {@code baseType} class literal supplied
 * at construction.
 *
 * <p>Generated factory code instantiates this class for each {@code Picker<T>}
 * parameter:
 * <pre>{@code
 * Picker<DataSource> sources = new ContainerPicker<>(container, container.options(), DataSource.class);
 * }</pre>
 *
 * <p>A picker answers for the whole application. Generated code runs inside one module's
 * container, so it also hands over that container's options: in a multi-module application they
 * name the container that aggregates the modules, and the picker asks that one (#732). A
 * single-module container is the application, and is asked itself.
 *
 * @param <T> the picker's base type
 */
public final class ContainerPicker<T> implements Picker<T> {

    private final Container container;
    private final TikoOptions options;
    private final Class<T> baseType;

    /** A picker over {@code container} alone; what code generated before 0.7.0 calls. */
    public ContainerPicker(Container container, Class<T> baseType) {
        this(container, null, baseType);
    }

    /**
     * @param container the container the generated code runs in
     * @param options that container's options, or {@code null} for a picker over {@code container} alone
     */
    public ContainerPicker(Container container, TikoOptions options, Class<T> baseType) {
        this.container = container;
        this.options = options;
        this.baseType = baseType;
    }

    /** Looked up on every call: the application's container builds its modules while it is being constructed. */
    private Container application() {
        var application = options == null ? null : options.applicationContainer();
        return application != null ? application : container;
    }

    @Override
    public List<T> list() {
        return application().getAll(baseType);
    }

    @Override
    public Optional<T> byName(String name) {
        try {
            return Optional.ofNullable(application().get(baseType, name));
        } catch (NoSuchComponentException notFound) {
            return Optional.empty();
        }
    }

    @Override
    public <S extends T> Optional<S> byImplClass(Class<S> implClass) {
        try {
            // container.get(implClass) returns an instance of implClass. The S extends T
            // bound guarantees Java-level type safety; the unchecked cast is for the
            // raw container call which is parameterised on its own argument.
            S bean = application().get(implClass);
            // Defensive: if a custom Container ever returns a non-T (shouldn't happen
            // because S extends T is enforced at the call site, but extra safety here),
            // treat it as not found rather than letting a ClassCastException leak out.
            if (!baseType.isInstance(bean)) {
                return Optional.empty();
            }
            return Optional.of(bean);
        } catch (NoSuchComponentException notFound) {
            return Optional.empty();
        }
    }
}
