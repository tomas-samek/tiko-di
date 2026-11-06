package io.tiko.processor.core;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.PropertyAccessorDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.Optional;
import java.util.Set;

/**
 * The Kotlin construct rule, shared by the DI and Kafka processors: a Kotlin construct that maps
 * 1:1 onto a Java construct Tiko supports is supported; one that would add semantics generated
 * Java can't express is rejected with a Tiko-format error. Each rule reads model facts only
 * ({@link ClassDecl#kind()}, {@link MethodDecl#isTopLevel()}, {@link MethodDecl#isExtension()},
 * {@link MethodDecl#isSuspend()}, {@link ClassDecl#isValueClass()}, {@link MethodDecl#jvmName()}), so none of them fires for Java
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

    private static final String VALUE_CLASS_FIX =
            "Use a regular class: drop @JvmInline and value (a data class keeps the value semantics)";

    private static final String PROXY_VALUE_CLASS_FIX =
            "Use a regular class in the interface: drop @JvmInline and value (a data class keeps the value semantics)";

    /** Java's reserved words and literals: a method with one of these names can't be called from Java source. */
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null",
            "_");

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

    /**
     * A value class can't be a component: on the JVM it is passed as its underlying value, so
     * generated Java can't construct, pass or return it as itself.
     */
    public static Optional<Violation> valueClassComponent(ClassDecl decl) {
        if (!decl.isValueClass()) {
            return Optional.empty();
        }
        return Optional.of(new Violation(
                decl.location(),
                displayName(decl) + " is a Kotlin value class: value classes can't be components. On the JVM"
                        + " a value class is passed as its underlying value, so generated Java can't construct,"
                        + " pass or return it as " + displayName(decl) + ".",
                "Make it a regular class: drop @JvmInline and value (a data class keeps the value semantics)"));
    }

    /**
     * A method returning or taking a value class: on the JVM the value class becomes its underlying
     * value and the function name is mangled, so the caller can't call it with the declared types.
     */
    public static Optional<Violation> valueClassInSignature(MethodDecl method, String annotationFqn, Caller caller) {
        var subject = label(annotationFqn) + " '" + qualifiedMethodName(method) + "'";
        if (isValueClass(method.returnType())) {
            return Optional.of(new Violation(
                    method.location(),
                    subject + " returns the Kotlin value class "
                            + method.returnType().displayName() + valueClassReason(caller),
                    VALUE_CLASS_FIX));
        }
        return method.parameters().stream()
                .filter(parameter -> isValueClass(parameter.type()))
                .findFirst()
                .map(parameter -> new Violation(
                        method.location(),
                        subject + " takes the Kotlin value class "
                                + parameter.type().displayName() + " (parameter '" + parameter.name() + "')"
                                + valueClassReason(caller),
                        VALUE_CLASS_FIX));
    }

    /**
     * An abstract member of a proxied interface (a method or a property accessor) returning or
     * taking a value class, or whose JVM name is mangled: the generated proxy overrides it by
     * its JVM signature, so it can't be written in Java.
     */
    public static Optional<Violation> valueClassInProxiedInterface(MethodDecl method, Caller caller) {
        var subject = "Proxied interface member '" + qualifiedMethodName(method) + "'";
        if (isValueClass(method.returnType())) {
            return Optional.of(new Violation(
                    method.location(),
                    subject + " returns the Kotlin value class "
                            + method.returnType().displayName() + valueClassReason(caller),
                    PROXY_VALUE_CLASS_FIX));
        }
        var valueClassParameter = method.parameters().stream()
                .filter(parameter -> isValueClass(parameter.type()))
                .findFirst();
        if (valueClassParameter.isPresent()) {
            var parameter = valueClassParameter.get();
            return Optional.of(new Violation(
                    method.location(),
                    subject + " takes the Kotlin value class "
                            + parameter.type().displayName() + " (parameter '" + parameter.name() + "')"
                            + valueClassReason(caller),
                    PROXY_VALUE_CLASS_FIX));
        }
        if (method.isConstructor() || isJavaIdentifier(method.jvmName())) {
            return Optional.empty();
        }
        return Optional.of(new Violation(
                method.location(),
                subject + " has the JVM name '" + method.jvmName() + "', which " + caller.generated()
                        + " can't override: it is not a Java identifier.",
                "Give it a Java-callable name with @JvmName(\"...\"), or avoid value classes and internal members"
                        + " in the interface"));
    }

    /**
     * An injection point of a value class type, such as a constructor parameter: generated Java
     * would pass the value class where the JVM signature takes its underlying value.
     *
     * @param consumer the injecting declaration's display name, as in "Service"
     */
    public static Optional<Violation> valueClassInjected(ParamDecl parameter, String consumer, Caller caller) {
        if (!isValueClass(parameter.type())) {
            return Optional.empty();
        }
        return Optional.of(new Violation(
                parameter.location(),
                consumer + " injects the Kotlin value class " + parameter.type().displayName() + " (parameter '"
                        + parameter.name() + "')" + valueClassReason(caller),
                VALUE_CLASS_FIX));
    }

    /**
     * A method whose JVM name generated Java source can't call (not a Java identifier, or a Java
     * keyword such as {@code new}); catches the mangled names the other rules don't name.
     */
    public static Optional<Violation> uncallableJvmName(MethodDecl method, String annotationFqn, Caller caller) {
        var jvmName = method.jvmName();
        if (method.isConstructor() || isJavaIdentifier(jvmName)) {
            return Optional.empty();
        }
        return Optional.of(new Violation(
                method.location(),
                label(annotationFqn) + " '" + qualifiedMethodName(method) + "' has the JVM name '" + jvmName
                        + "', which " + caller.generated() + " can't call: generated Java calls it by that name,"
                        + " and it is not a Java identifier.",
                "Rename the function, or give it a Java-callable name with @JvmName(\"...\")"));
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

    /** Whether generated Java source can call a method by this name: a Java identifier, not a keyword. */
    static boolean isJavaIdentifier(String name) {
        if (name.isEmpty() || JAVA_KEYWORDS.contains(name) || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        return name.chars().skip(1).allMatch(Character::isJavaIdentifierPart);
    }

    private static boolean isValueClass(TypeRef type) {
        return type.declaration().map(ClassDecl::isValueClass).orElse(false);
    }

    private static String valueClassReason(Caller caller) {
        return ": on the JVM a value class is passed as its underlying value and the functions taking or"
                + " returning one get mangled names, so " + caller.generated() + " can't use it.";
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
