package io.tiko;

/**
 * Thrown when an EVENT-scoped bean is resolved while no unit of work is open on the current
 * thread. EVENT beans live for exactly one unit of work — an open
 * {@link Container#runInEventScope(Runnable)} / {@link Container#supplyInEventScope} frame —
 * and their teardown ({@code @PreDestroy} / {@code AutoCloseable.close()}) runs when that frame
 * exits. Materializing one outside a frame would orphan the instance: it would never be drained,
 * silently leaking resources or handing stale per-unit data to the next unit of work scheduled
 * on the same (pooled) thread.
 *
 * <p>Raised by every resolution path that reaches an EVENT-scoped bean: direct
 * {@code container.get(...)} / {@code getAll(...)} lookups, a {@code Provider} obtained from the
 * container, and method calls on a cross-scope proxy held by a SINGLETON.
 *
 * <p>Carries the requested {@link #componentKey()} as a structured field, so a caller can react
 * programmatically rather than parsing the message.
 */
public final class NoActiveEventScopeException extends TikoException {

    private final String componentKey;

    public NoActiveEventScopeException(String componentKey) {
        super("EVENT-scoped bean '" + componentKey + "' was requested outside a unit of work. "
                + "EVENT beans live for exactly one unit of work - open one with "
                + "container.runInEventScope(...) or supplyInEventScope(...) and resolve the bean inside it.");
        this.componentKey = componentKey;
    }

    /** The storage key of the requested bean: its qualified class name, or {@code fqn#name} when named. */
    public String componentKey() {
        return componentKey;
    }
}
