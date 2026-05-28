package io.tiko;

/**
 * Default {@link Pick} implementation backed by {@link Container#get(Class)} /
 * {@link Container#get(Class, String)}. Immutable — each axis call returns a
 * new instance so a {@code Pick} can be safely shared.
 */
final class DefaultPick<T> implements Pick<T> {

    private final Container container;
    private final Class<T> type;
    private final String name;

    DefaultPick(Container container, Class<T> type) {
        this(container, type, null);
    }

    private DefaultPick(Container container, Class<T> type, String name) {
        this.container = container;
        this.type = type;
        this.name = name;
    }

    @Override
    public Pick<T> withName(String name) {
        return new DefaultPick<>(container, type, name);
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
