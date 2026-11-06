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
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;
import java.util.List;
import java.util.Optional;

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
 * declaring class). The rules themselves are {@link KotlinConstructRules}, shared with the Kafka
 * processor; they decide on model facts only, and on javac none of them occur, so it reports
 * nothing for Java sources.
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
                KotlinConstructRules.objectComponent(decl).ifPresent(violation -> reject(decl, violation));
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
            }
        }
        for (var annotation : FUNCTION_ANNOTATIONS) {
            for (var accessor : env.propertyAccessorsAnnotatedWith(annotation)) {
                report(KotlinConstructRules.propertyAccessor(accessor, annotation));
            }
        }
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
