package io.tiko.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level marker that wraps a {@code @Test} invocation in
 * {@link io.tiko.Container#runInRequestScope(Runnable)}.
 *
 * <p>Useful when the test body needs a REQUEST-scoped bean to be resolvable. Combine with
 * {@link EventScopeTest} on the same method to nest an event scope inside the request scope.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequestScopeTest {}
