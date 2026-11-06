package io.tiko.processor.validation;

import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import io.tiko.annotations.Produces;
import io.tiko.processor.collect.ComponentCollector;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;
import java.util.List;

/**
 * Rejects the Kotlin constructs that would add DI semantics Java has no counterpart for (the
 * construct rule of the Kotlin support): an {@code object} or {@code companion object} as a
 * component, a {@code @Produces} or {@code @EventHandler} that is a top-level function or lives
 * in an object, an extension or {@code suspend} DI method, and DI annotations on property accessors
 * ({@code @get:} / {@code @set:}). Each is reported as {@link ErrorReporter#KOTLIN_UNSUPPORTED}
 * on its declaration and marked rejected in the {@link ProcessorContext}, so the collectors that
 * run after it build no model for it.
 *
 * <p>It runs on each round's annotated declarations <em>before</em> collection, because the
 * collectors assume what it rules out (a {@code @Produces} or {@code @EventHandler} always has a
 * declaring class). It decides on model facts only ({@link ClassDecl#kind()},
 * {@link MethodDecl#isTopLevel()}, {@link MethodDecl#isExtension()}, {@link MethodDecl#isSuspend()},
 * {@link ProcessingEnv#propertyAccessorsAnnotatedWith}); on javac none of them occur, so it
 * reports nothing for Java sources.
 */
public final class KotlinConstructValidator {

    private static final String COMPONENT = Component.class.getCanonicalName();
    private static final String PRODUCES = Produces.class.getCanonicalName();
    private static final String EVENT_HANDLER = EventHandler.class.getCanonicalName();
    private static final String POST_CONSTRUCT = PostConstruct.class.getCanonicalName();
    private static final String PRE_DESTROY = PreDestroy.class.getCanonicalName();
    private static final String INJECT = Inject.class.getCanonicalName();

    /** The DI annotations that mark functions; on a property accessor each is rejected. */
    private static final List<String> FUNCTION_ANNOTATIONS =
            List.of(PRODUCES, EVENT_HANDLER, POST_CONSTRUCT, PRE_DESTROY, INJECT);

    private static final String MOVE_INTO_COMPONENT = "Make it a member function: move it into a @Component class";
    private static final String MOVE_TO_ENCLOSING =
            "Make it a member function: move it to the enclosing @Component class";
    private static final String DROP_SUSPEND = "Drop the suspend modifier: make it a regular fun";

    private final ProcessorContext context;
    private final ErrorReporter errors;

    public KotlinConstructValidator(ProcessorContext context) {
        this.context = context;
        this.errors = context.getErrorReporter();
    }

    /** Reports and rejects this round's unsupported Kotlin constructs. */
    public void validate(ProcessingEnv env) {
        for (var annotation : List.of(COMPONENT, ComponentCollector.TEST_COMPONENT_FQN)) {
            for (var decl : env.classesAnnotatedWith(annotation)) {
                validateComponent(decl);
            }
        }
        for (var annotation : List.of(PRODUCES, EVENT_HANDLER)) {
            for (var method : env.methodsAnnotatedWith(annotation)) {
                validateOwnedMethod(method, annotation);
            }
        }
        for (var annotation : List.of(PRODUCES, EVENT_HANDLER, POST_CONSTRUCT, PRE_DESTROY)) {
            for (var method : env.methodsAnnotatedWith(annotation)) {
                validateNotExtension(method, annotation);
                validateNotSuspend(method, annotation);
            }
        }
        for (var annotation : FUNCTION_ANNOTATIONS) {
            for (var accessor : env.propertyAccessorsAnnotatedWith(annotation)) {
                errors.error(
                        ErrorReporter.KOTLIN_UNSUPPORTED,
                        accessor.location(),
                        label(annotation) + " on the " + (accessor.setter() ? "setter" : "getter") + " of property '"
                                + accessor.propertyName() + "' is not supported: Tiko reads DI annotations on"
                                + " functions, not on property accessors (@get: / @set:).",
                        "Move the annotation to a function: declare a fun instead");
            }
        }
    }

    /** An object or companion object can't be a component: Tiko creates and scopes component instances. */
    private void validateComponent(ClassDecl decl) {
        if (!isObject(decl)) {
            return;
        }
        errors.error(
                ErrorReporter.KOTLIN_UNSUPPORTED,
                decl.location(),
                displayName(decl) + " is " + objectKind(decl) + ": object declarations can't be components."
                        + " Tiko creates and scopes every component instance, and an object is a single"
                        + " instance Kotlin creates itself.",
                "To share one instance, use a class with scope = SINGLETON");
        context.reject(decl);
    }

    /**
     * A {@code @Produces} / {@code @EventHandler} needs a component instance to be called on: a
     * top-level function has none, and an object can't be a component.
     */
    private void validateOwnedMethod(MethodDecl method, String annotation) {
        var label = label(annotation);
        if (method.isTopLevel()) {
            reject(
                    method,
                    label + " on a top-level function '" + method.name() + "' is not supported: Tiko calls it on"
                            + " the instance of its @Component class, and a top-level function has none.",
                    MOVE_INTO_COMPONENT);
            return;
        }
        var owner = method.owner().filter(KotlinConstructValidator::isObject);
        if (owner.isEmpty()) {
            return;
        }
        var companion = owner.get().kind() == DeclKind.KOTLIN_COMPANION;
        reject(
                method,
                label + " inside " + (companion ? "a companion object" : "an object declaration") + " ('"
                        + method.name() + "' in " + displayName(owner.get()) + ") is not supported: object"
                        + " declarations can't be components, so Tiko has no component instance to call it on.",
                companion ? MOVE_TO_ENCLOSING : MOVE_INTO_COMPONENT);
    }

    /**
     * Generated Java would have to pass an extension function's receiver, which no injection point
     * declares; a regular function taking it as a parameter maps 1:1 onto Java.
     */
    private void validateNotExtension(MethodDecl method, String annotation) {
        if (!method.isExtension() || context.isRejected(method)) {
            return;
        }
        reject(
                method,
                label(annotation) + " on the extension function '" + qualifiedMethodName(method) + "' is not"
                        + " supported: the generated container would have to pass its receiver, and Tiko injects"
                        + " parameters only.",
                "Drop the receiver: declare a regular fun that takes the receiver as a parameter");
    }

    /** Generated Java calls DI methods directly, without a coroutine; suspend handlers come with K2. */
    private void validateNotSuspend(MethodDecl method, String annotation) {
        if (!method.isSuspend() || context.isRejected(method)) {
            return;
        }
        var name = qualifiedMethodName(method);
        if (annotation.equals(EVENT_HANDLER)) {
            reject(
                    method,
                    "suspend @EventHandler is not supported yet: '" + name + "' is a suspend fun, and"
                            + " suspend handlers arrive with Tiko's coroutine support (K2).",
                    "Drop the suspend modifier: make it a regular fun, or wait for coroutine support (K2)");
            return;
        }
        reject(
                method,
                "suspend " + label(annotation) + " is not supported: '" + name + "' is a suspend fun, and the"
                        + " generated container calls it directly, outside any coroutine.",
                DROP_SUSPEND);
    }

    private void reject(MethodDecl method, String problem, String fix) {
        errors.error(ErrorReporter.KOTLIN_UNSUPPORTED, method.location(), problem, fix);
        context.reject(method);
    }

    private static boolean isObject(ClassDecl decl) {
        return decl.kind() == DeclKind.KOTLIN_OBJECT || decl.kind() == DeclKind.KOTLIN_COMPANION;
    }

    private static String objectKind(ClassDecl decl) {
        return decl.kind() == DeclKind.KOTLIN_COMPANION ? "a companion object" : "an object declaration";
    }

    /** The class's simple names from the outermost class, such as {@code Host.Companion}. */
    private static String displayName(ClassDecl decl) {
        return String.join(".", decl.className().simpleNames());
    }

    private static String qualifiedMethodName(MethodDecl method) {
        return method.owner()
                .map(owner -> displayName(owner) + "." + method.name())
                .orElse(method.name());
    }

    /** {@code @Produces} for {@code io.tiko.annotations.Produces}. */
    private static String label(String annotationFqn) {
        return "@" + annotationFqn.substring(annotationFqn.lastIndexOf('.') + 1);
    }
}
