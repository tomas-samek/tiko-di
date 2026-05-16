package io.tiko.runtime;

/**
 * Internal helper used by Tiko-generated code to propagate checked
 * exceptions across method boundaries that don't declare them. Not part of
 * the public API surface — generated code is the only intended caller.
 *
 * <p>This sidesteps the type-system cascade that would otherwise force
 * {@code Container#get(Class)}, every {@code Provider<T>}, and every
 * intermediate factory accessor to declare {@code throws Throwable}. The
 * user's original throwable propagates as itself; callers handle it via
 * {@code catch (Exception e)} or {@code instanceof}.
 */
public final class Unchecked {

    private Unchecked() {}

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T sneakyThrow(Throwable e) throws T {
        throw (T) e;
    }
}
