package io.tiko.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level marker that wraps a {@code @Test} invocation in
 * {@link io.tiko.Container#runInEventScope(Runnable)}.
 *
 * <p>Useful when the test body needs an EVENT-scoped bean to be resolvable.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventScopeTest {}
