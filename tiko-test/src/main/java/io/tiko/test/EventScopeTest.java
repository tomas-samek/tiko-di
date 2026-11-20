package io.tiko.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level marker that runs a test invocation in a unit of work (the EVENT scope, not a bus
 * event), through {@link io.tiko.Container#runInEventScope(Runnable)}: a {@code @Test}, each invocation of a
 * {@code @RepeatedTest} or {@code @ParameterizedTest}, and each dynamic test of a
 * {@code @TestFactory}.
 *
 * <p>Useful when the test body needs an EVENT-scoped bean to be resolvable.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventScopeTest {}
