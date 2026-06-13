package io.tiko;

/**
 * Default {@link PickBuilder} implementation backed by {@link Container#get(Class)} /
 * {@link Container#get(Class, String)}. Immutable — each axis call returns a
 * new instance so a {@code PickBuilder} can be safely shared.
 */
final class DefaultPickBuilder<T> implements PickBuilder<T> {

    private final Container container;
    private final Class<T> type;
    private final String name;

    DefaultPickBuilder(Container container, Class<T> type) {
        this(container, type, null);
    }

    private DefaultPickBuilder(Container container, Class<T> type, String name) {
        this.container = container;
        this.type = type;
        this.name = name;
    }

    @Override
    public PickBuilder<T> withName(String name) {
        return new DefaultPickBuilder<>(container, type, name);
    }

    @Override
    public T resolve() {
        return name == null ? container.get(type) : container.get(type, name);
    }

    @Override
    public Provider<T> asProvider() {
        return this::resolve;
    }

    @Override
    public T orDefault(T fallback) {
        try {
            return resolve();
        } catch (NoSuchComponentException missing) {
            return fallback;
        }
    }
}
