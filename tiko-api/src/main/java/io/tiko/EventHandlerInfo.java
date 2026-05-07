package io.tiko;

/**
 * Identifies an {@code @EventHandler} method for diagnostic purposes — used inside
 * {@link EventHandlerError} to tell error-handling code which handler threw, without
 * exposing reflection types.
 *
 * <p>Populated at compile time by the annotation processor; framework code does not
 * read these fields, so they remain accurate even if reflection is later disabled.
 *
 * @param declaringClass class declaring the {@code @EventHandler} method
 * @param methodName     simple method name (no descriptor, no class prefix)
 * @param eventType      class of the event the handler subscribes to
 * @param async          whether the handler was declared {@code @EventHandler(async = true)}
 */
public record EventHandlerInfo(
    Class<?> declaringClass,
    String methodName,
    Class<?> eventType,
    boolean async
) {}
