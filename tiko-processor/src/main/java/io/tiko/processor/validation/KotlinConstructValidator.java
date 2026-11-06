package io.tiko.processor.validation;

import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import io.tiko.annotations.Produces;
import io.tiko.processor.collect.ComponentCollector;
import io.tiko.processor.core.KotlinConstructRules;
import io.tiko.processor.core.KotlinConstructRules.Caller;
import io.tiko.processor.core.KotlinConstructRules.Violation;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.util.AbstractInterfaceMethods;
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Rejects the Kotlin constructs that would add DI semantics Java has no counterpart for (the
 * construct rule of the Kotlin support): an {@code object} or {@code companion object} as a
 * component, a {@code @Produces} or {@code @EventHandler} that is a top-level function or lives
 * in an object, an extension or {@code suspend} DI method, a value class as a component or in a
 * DI method's signature, a DI method whose JVM name Java can't call, and DI annotations on
 * property accessors ({@code @get:} / {@code @set:}). Each is reported as {@link ErrorReporter#KOTLIN_UNSUPPORTED}
 * on its declaration and marked rejected in the {@link ProcessorContext}, so the collectors that
 * run after it build no model for it.
 *
 * <p>It runs on each round's annotated declarations <em>before</em> collection, because the
 * collectors assume what it rules out (a {@code @Produces} or {@code @EventHandler} always has a
 * declaring class). The rules themselves are {@link KotlinConstructRules}, shared with the Kafka
 * processor; they decide on model facts only, and on javac none of them occur, so it reports
 * nothing for Java sources. Value-class injection points are checked after collection, by
 * {@link #validateInjectionPoints}, once the injected constructor is known.
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

    /** The generated code calling DI methods. */
    private static final Caller CONTAINER = new Caller("the generated container", "Tiko injects parameters only");

    /** The generated proxy overriding a proxied interface's members. */
    private static final Caller PROXY = new Caller("the generated proxy", "Tiko proxies interface members only");

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
                KotlinConstructRules.objectComponent(decl)
                        .or(() -> KotlinConstructRules.valueClassComponent(decl))
                        .ifPresent(violation -> reject(decl, violation));
            }
        }
        for (var annotation : List.of(PRODUCES, EVENT_HANDLER)) {
            for (var method : env.methodsAnnotatedWith(annotation)) {
                reject(method, KotlinConstructRules.needsComponentInstance(method, annotation));
            }
        }
        for (var annotation : List.of(PRODUCES, EVENT_HANDLER, POST_CONSTRUCT, PRE_DESTROY)) {
            for (var method : env.methodsAnnotatedWith(annotation)) {
                reject(method, KotlinConstructRules.extension(method, annotation, CONTAINER));
                reject(method, suspendViolation(method, annotation));
                reject(method, KotlinConstructRules.valueClassInSignature(method, annotation, CONTAINER));
                reject(method, KotlinConstructRules.uncallableJvmName(method, annotation, CONTAINER));
            }
        }
        for (var annotation : FUNCTION_ANNOTATIONS) {
            for (var accessor : env.propertyAccessorsAnnotatedWith(annotation)) {
                report(KotlinConstructRules.propertyAccessor(accessor, annotation));
            }
        }
    }

    /**
     * Reports the collected components that inject a Kotlin value class: generated Java would pass
     * it where the JVM constructor takes its underlying value. It runs after collection, which
     * picks the injected constructor; a {@code @Produces} or handler taking one was already
     * rejected by {@link #validate}.
     *
     * @return {@code false} when one was reported
     */
    public boolean validateInjectionPoints() {
        var valid = true;
        for (var component : context.getActiveComponents()) {
            for (var dependency : component.getDependencies()) {
                var violation = KotlinConstructRules.valueClassInjected(
                        dependency.getParameter(), component.getClassName(), CONTAINER);
                if (violation.isPresent()) {
                    report(violation.get());
                    valid = false;
                }
            }
        }
        return validateProxiedInterfaces() && valid;
    }

    /**
     * Reports the abstract members of each proxied interface that name a value class or a mangled
     * JVM name: the generated proxy would override them by JVM signature, which Java source can't
     * write. It runs before proxy generation, so no proxy is written for a rejected interface.
     */
    private boolean validateProxiedInterfaces() {
        var interfaces = new LinkedHashMap<String, ClassDecl>();
        for (var component : context.getActiveComponents()) {
            if (component.requiresProxy()) {
                component
                        .getImplementedInterface()
                        .flatMap(type -> context.env().types().findClass(type.displayName()))
                        .ifPresent(
                                decl -> interfaces.putIfAbsent(decl.className().canonicalName(), decl));
            }
        }
        for (var factory : context.getActiveFactoryMethods()) {
            if (factory.requiresProxy()) {
                context.env()
                        .types()
                        .findClass(factory.getReturnTypeName())
                        .ifPresent(
                                decl -> interfaces.putIfAbsent(decl.className().canonicalName(), decl));
            }
        }
        var valid = true;
        for (var decl : interfaces.values()) {
            for (var method : AbstractInterfaceMethods.collect(decl)) {
                var violation = KotlinConstructRules.valueClassInProxiedInterface(method, PROXY);
                if (violation.isPresent()) {
                    report(violation.get());
                    valid = false;
                }
            }
        }
        return valid;
    }

    /** Suspend handlers come with K2; every other DI method is called directly, without a coroutine. */
    private static Optional<Violation> suspendViolation(MethodDecl method, String annotation) {
        return annotation.equals(EVENT_HANDLER)
                ? KotlinConstructRules.suspendUntilK2(method, annotation, "handlers")
                : KotlinConstructRules.suspendCalledDirectly(method, annotation, CONTAINER);
    }

    private void reject(ClassDecl decl, Violation violation) {
        report(violation);
        context.reject(decl);
    }

    /** Reports and rejects the method, unless an earlier rule already did. */
    private void reject(MethodDecl method, Optional<Violation> violation) {
        if (violation.isEmpty() || context.isRejected(method)) {
            return;
        }
        report(violation.get());
        context.reject(method);
    }

    private void report(Violation violation) {
        errors.error(ErrorReporter.KOTLIN_UNSUPPORTED, violation.location(), violation.problem(), violation.fix());
    }
}
