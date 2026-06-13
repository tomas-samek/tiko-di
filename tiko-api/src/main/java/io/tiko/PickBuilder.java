package io.tiko;

/**
 * Fluent multi-axis lookup builder for {@link Container}.
 *
 * <p>{@code pick(Class)} is the entry point for any lookup that goes beyond the
 * 80% shortcuts {@link Container#get(Class)} and {@link Container#get(Class, String)}.
 * It composes axes (name) and chooses a terminal:
 *
 * <ul>
 *   <li>{@link #resolve()} — eager resolve, mirrors {@code get}</li>
 *   <li>{@link #asProvider()} — lazy {@link Provider} that re-resolves on each call</li>
 *   <li>{@link #orDefault(Object)} — return a fallback when no matching bean exists</li>
 * </ul>
 *
 * <p>{@code container.pick(Type.class).resolve()} with no axes is equivalent to
 * {@code container.get(Type.class)}.
 *
 * @param <T> the bean type
 */
public interface PickBuilder<T> {

    /**
     * Adds a qualifier name to the query.
     */
    PickBuilder<T> withName(String name);

    /**
     * Resolves the bean, throwing if no match exists.
     */
    T resolve();

    /**
     * Returns a {@link Provider} that defers resolution to each call,
     * preserving scope semantics.
     */
    Provider<T> asProvider();

    /**
     * Resolves the bean, returning the given fallback if no match exists.
     */
    T orDefault(T fallback);
}
