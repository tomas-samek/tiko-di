package io.tiko.examples.basic.teardown;

import java.io.IOException;

/**
 * The event {@link ThrowingCheckedAsyncHandler} fails on. It carries the exception to throw, so
 * that {@code CheckedExceptionPropagationTest} can check the handler's failure by identity.
 */
public record CheckedAsyncPing(IOException toThrow) {}
