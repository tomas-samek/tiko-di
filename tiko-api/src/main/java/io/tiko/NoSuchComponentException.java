package io.tiko;

/**
 * Thrown when a component lookup ({@code container.get(...)}, {@code getProvider(...)}, a resolved
 * {@code Pick}) finds no matching bean — the requested type is not registered, not exposed for
 * injection, or no provider matches the given qualifier.
 *
 * <p>Carries the requested {@link #type()} and optional {@link #qualifier()} as structured fields,
 * so a caller can react programmatically rather than parsing the message.
 */
public final class NoSuchComponentException extends TikoException {

    private final Class<?> type;
    private final String qualifier;

    public NoSuchComponentException(Class<?> type) {
        this(type, null);
    }

    public NoSuchComponentException(Class<?> type, String qualifier) {
        super(buildMessage(type, qualifier));
        this.type = type;
        this.qualifier = qualifier;
    }

    /** The requested component type. */
    public Class<?> type() {
        return type;
    }

    /** The qualifier ({@code @Named} / pick name) requested, or {@code null} for an unqualified lookup. */
    public String qualifier() {
        return qualifier;
    }

    private static String buildMessage(Class<?> type, String qualifier) {
        String base = "No component found for type: " + (type == null ? "null" : type.getName());
        return qualifier == null ? base : base + " with name: " + qualifier;
    }
}
