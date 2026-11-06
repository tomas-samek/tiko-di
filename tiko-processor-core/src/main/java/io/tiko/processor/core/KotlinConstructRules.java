package io.tiko.processor.core;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.PropertyAccessorDecl;
import java.util.Optional;

/**
 * The Kotlin construct rule, shared by the DI and Kafka processors: a Kotlin construct that maps
 * 1:1 onto a Java construct Tiko supports is supported; one that would add semantics generated
 * Java can't express is rejected with a Tiko-format error. Each rule reads model facts only
 * ({@link ClassDecl#kind()}, {@link MethodDecl#isTopLevel()}, {@link MethodDecl#isExtension()},
 * {@link MethodDecl#isSuspend()}), so none of them fires for Java
 * sources, and returns the {@link Violation} to report; the processors report it (DI as a
 * {@link #KIND} wiring error) and leave the declaration out of collection.
 *
 * <p>A rule that names the generated code calling a declaration takes a {@link Caller}: the DI
 * container, or the Kafka bootstrap.
 */
public final class KotlinConstructRules {

    /** The error kind of every violation: DI's {@code wiring-errors.json} kind. */
    public static final String KIND = "KOTLIN_UNSUPPORTED";

    private static final String MOVE_INTO_COMPONENT = "Make it a member function: move it into a @Component class";
    private static final String MOVE_TO_ENCLOSING =
            "Make it a member function: move it to the enclosing @Component class";
    private static final String DROP_SUSPEND = "Drop the suspend modifier: make it a regular fun";

    private KotlinConstructRules() {}

    /**
     * An unsupported construct: where it is, what's wrong, and one fix.
     *
     * @param location the declaration to report on
     * @param problem what's wrong and why
     * @param fix the suggested fix
     */
    public record Violation(Location location, String problem, String fix) {

        /** The Tiko error format: the problem, then the numbered suggested fix. */
        public String message() {
            return problem + "\n\nSuggested fixes:\n1. " + fix;
        }
    }

    /**
     * The generated code that calls a declaration.
     *
     * @param generated what calls it, as in "the generated container"
     * @param passes what it passes, as in "Tiko injects parameters only"
     */
    public record Caller(String generated, String passes) {}

    /** An object or companion object can't be a component: Tiko creates and scopes component instances. */
    public static Optional<Violation> objectComponent(ClassDecl decl) {
        if (!isObject(decl)) {
            return Optional.empty();
        }
        return Optional.of(new Violation(
                decl.location(),
                displayName(decl) + " is " + objectKind(decl) + ": object declarations can't be components."
                        + " Tiko creates and scopes every component instance, and an object is a single"
                        + " instance Kotlin creates itself.",
                "To share one instance, use a class with scope = SINGLETON"));
    }

    /**
     * A method Tiko calls on its component's instance: a top-level function has none, and an
     * object can't be a component.
     */
    public static Optional<Violation> needsComponentInstance(MethodDecl method, String annotationFqn) {
        var label = label(annotationFqn);
        if (method.isTopLevel()) {
            return Optional.of(new Violation(
                    method.location(),
                    label + " on a top-level function '" + method.name() + "' is not supported: Tiko calls it on"
                            + " the instance of its @Component class, and a top-level function has none.",
                    MOVE_INTO_COMPONENT));
        }
        var owner = method.owner().filter(KotlinConstructRules::isObject);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        var companion = owner.get().kind() == DeclKind.KOTLIN_COMPANION;
        return Optional.of(new Violation(
                method.location(),
                label + " inside " + (companion ? "a companion object" : "an object declaration") + " ('"
                        + method.name() + "' in " + displayName(owner.get()) + ") is not supported: object"
                        + " declarations can't be components, so Tiko has no component instance to call it on.",
                companion ? MOVE_TO_ENCLOSING : MOVE_INTO_COMPONENT));
    }

    /**
     * Generated Java would have to pass an extension function's receiver, which nothing declares;
     * a regular function taking it as a parameter maps 1:1 onto Java.
     */
    public static Optional<Violation> extension(MethodDecl method, String annotationFqn, Caller caller) {
        if (!method.isExtension()) {
            return Optional.empty();
        }
        return Optional.of(new Violation(
                method.location(),
                label(annotationFqn) + " on the extension function '" + qualifiedMethodName(method) + "' is not"
                        + " supported: " + caller.generated() + " would have to pass its receiver, and "
                        + caller.passes() + ".",
                "Drop the receiver: declare a regular fun that takes the receiver as a parameter"));
    }

    /**
     * A {@code suspend} function that coroutine support (K2) will accept: until then it is
     * rejected with a pointer to K2.
     *
     * @param plural what K2 brings, as in "handlers"
     */
    public static Optional<Violation> suspendUntilK2(MethodDecl method, String annotationFqn, String plural) {
        if (!method.isSuspend()) {
            return Optional.empty();
        }
        return Optional.of(new Violation(
                method.location(),
                "suspend " + label(annotationFqn) + " is not supported yet: '" + qualifiedMethodName(method)
                        + "' is a suspend fun, and suspend " + plural + " arrive with Tiko's coroutine support"
                        + " (K2).",
                "Drop the suspend modifier: make it a regular fun, or wait for coroutine support (K2)"));
    }

    /** A {@code suspend} function generated Java calls directly, outside any coroutine. */
    public static Optional<Violation> suspendCalledDirectly(MethodDecl method, String annotationFqn, Caller caller) {
        if (!method.isSuspend()) {
            return Optional.empty();
        }
        return Optional.of(new Violation(
                method.location(),
                "suspend " + label(annotationFqn) + " is not supported: '" + qualifiedMethodName(method)
                        + "' is a suspend fun, and " + caller.generated() + " calls it directly, outside any"
                        + " coroutine.",
                DROP_SUSPEND));
    }

    /** Tiko reads DI annotations on functions; an accessor ({@code @get:} / {@code @set:}) is not one. */
    public static Violation propertyAccessor(PropertyAccessorDecl accessor, String annotationFqn) {
        return new Violation(
                accessor.location(),
                label(annotationFqn) + " on the " + (accessor.setter() ? "setter" : "getter") + " of property '"
                        + accessor.propertyName() + "' is not supported: Tiko reads DI annotations on"
                        + " functions, not on property accessors (@get: / @set:).",
                "Move the annotation to a function: declare a fun instead");
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
