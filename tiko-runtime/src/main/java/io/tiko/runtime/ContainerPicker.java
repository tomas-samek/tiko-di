package io.tiko.runtime;

import io.tiko.Container;
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
 * Picker<DataSource> sources = new ContainerPicker<>(container, DataSource.class);
 * }</pre>
 *
 * <p>Cross-module pickers work transparently — the underlying
 * {@link Container#get(Class)} / {@link Container#getAll(Class)} / etc. route through
 * the aggregator when multiple modules are linked.
 *
 * @param <T> the picker's base type
 */
public final class ContainerPicker<T> implements Picker<T> {

    private final Container container;
    private final Class<T> baseType;

    public ContainerPicker(Container container, Class<T> baseType) {
        this.container = container;
        this.baseType = baseType;
    }

    @Override
    public List<T> list() {
        return container.getAll(baseType);
    }

    @Override
    public Optional<T> byName(String name) {
        try {
            return Optional.ofNullable(container.get(baseType, name));
        } catch (IllegalArgumentException notFound) {
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends T> Optional<S> byImplClass(Class<S> implClass) {
        try {
            // container.get(implClass) returns an instance of implClass. The S extends T
            // bound guarantees Java-level type safety; the unchecked cast is for the
            // raw container call which is parameterised on its own argument.
            S bean = (S) container.get(implClass);
            // Defensive: if a custom Container ever returns a non-T (shouldn't happen
            // because S extends T is enforced at the call site, but extra safety here),
            // treat it as not found rather than letting a ClassCastException leak out.
            if (!baseType.isInstance(bean)) {
                return Optional.empty();
            }
            return Optional.of(bean);
        } catch (IllegalArgumentException notFound) {
            return Optional.empty();
        }
    }
}
