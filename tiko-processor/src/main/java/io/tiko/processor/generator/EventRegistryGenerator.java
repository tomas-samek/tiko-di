package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.Event;
import io.tiko.EventBus;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.model.EventTriggerModel;
import io.tiko.processor.util.GeneratorAnnotations;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Generates the EventRegistry class that registers all @EventHandler methods.
 *
 * <p>For every handler the generator emits a private static helper that:
 * <ol>
 *   <li>wraps the incoming payload in an {@link io.tiko.Event} chained to whatever
 *       wrapper is currently being delivered on this thread,</li>
 *   <li>installs that wrapper as the current chain root for the duration of the
 *       handler invocation,</li>
 *   <li>invokes the handler (with or without the {@code Event<?>} wrapper parameter),</li>
 *   <li>for each {@code @EventTrigger}, evaluates guards, optionally spreads
 *       collections, and publishes synchronously or via the async executor.</li>
 * </ol>
 * The {@code registerHandlers} method only contains lightweight {@code subscribe}
 * calls that delegate to those helpers — keeps the generated file readable.
 */
public final class EventRegistryGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated";

    private static final ClassName CHAIN_CONTEXT = ClassName.get("io.tiko.runtime", "EventChainContext");

    private final ProcessorContext context;

    public EventRegistryGenerator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Container-scoped registry class name. Each generated container gets its own
     * {@code EventRegistry_<hash>} so multi-module classpaths (main + standalone test
     * container, two production modules, etc.) can each carry their handler set without
     * colliding on a single {@code io.tiko.generated.EventRegistry} slot.
     */
    private String registryClassName() {
        String containerName = context.getContainerClassName();
        int underscore = containerName.lastIndexOf('_');
        String suffix = underscore >= 0 ? containerName.substring(underscore) : "";
        return "EventRegistry" + suffix;
    }

    public void generate() throws IOException {
        List<EventHandlerModel> eventHandlers = context.getEventHandlers();

        if (eventHandlers.isEmpty()) {
            return;
        }

        TypeSpec.Builder registry = TypeSpec.classBuilder(registryClassName())
                .addAnnotation(GeneratorAnnotations.generatedBy(EventRegistryGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        registry.addMethod(createRegisterMethod(eventHandlers));

        ClassName eventHandlerInfo = ClassName.get("io.tiko", "EventHandlerInfo");

        // Emit one HANDLER_INFO_<n> static constant per handler
        for (int i = 0; i < eventHandlers.size(); i++) {
            EventHandlerModel handler = eventHandlers.get(i);
            ClassName declaring = ClassName.bestGuess(
                    handler.getDeclaringClass().getQualifiedName().toString());
            ClassName eventClass = ClassName.bestGuess(handler.getEventTypeName());

            FieldSpec info = FieldSpec.builder(
                            eventHandlerInfo, "HANDLER_INFO_" + i, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(
                            "new $T($T.class, $S, $T.class, $L)",
                            eventHandlerInfo,
                            declaring,
                            handler.getMethodName(),
                            eventClass,
                            handler.isAsync())
                    .build();
            registry.addField(info);
        }

        // Emit one private helper per handler
        for (int i = 0; i < eventHandlers.size(); i++) {
            registry.addMethod(createDispatcherMethod(eventHandlers.get(i), i));
        }

        JavaFile.builder(GENERATED_PACKAGE, registry.build()).build().writeTo(context.getFiler());
    }

    /**
     * The public entry point: subscribes one lambda per handler that delegates to a helper.
     */
    private MethodSpec createRegisterMethod(List<EventHandlerModel> eventHandlers) {
        ClassName containerClass = ClassName.get(GENERATED_PACKAGE, context.getContainerClassName());

        MethodSpec.Builder method = MethodSpec.methodBuilder("registerHandlers")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get(EventBus.class), "eventBus")
                .addParameter(containerClass, "container");

        method.addComment("Register all event handlers");

        for (int i = 0; i < eventHandlers.size(); i++) {
            EventHandlerModel handler = eventHandlers.get(i);
            ClassName eventClass = ClassName.bestGuess(handler.getEventTypeName());
            method.addStatement(
                    "eventBus.subscribe($T.class, event -> $L(eventBus, container, event))",
                    eventClass,
                    dispatcherName(handler, i));
        }

        return method.build();
    }

    /** True when the handler's declaring component is a proxied EVENT bean (#331). */
    private boolean isProxiedComponent(EventHandlerModel handler) {
        String fqn = handler.getDeclaringClass().getQualifiedName().toString();
        return context.getComponents().values().stream()
                .anyMatch(c -> c.getQualifiedName().equals(fqn) && c.requiresProxy());
    }

    /**
     * The per-handler helper. Centralises chain-context bookkeeping and trigger logic so
     * the lambdas in {@code registerHandlers} stay one-liners.
     *
     * <p>Async handlers ({@code @EventHandler(async = true)}) delegate to
     * {@code EventChainContext.runAsyncWithTimeout} / {@code runAsyncWithRetry} (a plain async
     * handler uses a zero timeout budget), which submit to the container's event executor, apply the
     * configured overflow policy, and route any failure to the container's {@link io.tiko.ErrorHandler}.
     * Sync handlers keep the original inline try/catch behaviour.
     */
    private MethodSpec createDispatcherMethod(EventHandlerModel handler, int index) {
        ClassName containerClass = ClassName.get(GENERATED_PACKAGE, context.getContainerClassName());
        ClassName eventClass = ClassName.bestGuess(handler.getEventTypeName());
        ClassName declaringClass = ClassName.bestGuess(
                handler.getDeclaringClass().getQualifiedName().toString());
        // Proxied EVENT components: the plain getter returns the interface-typed proxy, which
        // cannot be assigned to the concrete handler variable — resolve the current unit's
        // instance via the concrete-typed getCurrentXxx delegate instead (#331).
        String getterPrefix = isProxiedComponent(handler) ? "getCurrent" : "get";
        String getterName = getterPrefix + handler.getDeclaringClass().getSimpleName();

        ClassName errorHandler = ClassName.get("io.tiko", "ErrorHandler");
        ClassName eventHandlerError = ClassName.get("io.tiko", "EventHandlerError");
        ClassName executorServiceClass = ClassName.get(ExecutorService.class);

        MethodSpec.Builder method = MethodSpec.methodBuilder(dispatcherName(handler, index))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(ClassName.get(EventBus.class), "eventBus")
                .addParameter(containerClass, "container")
                .addParameter(eventClass, "event");

        // Subscriptions are never released from the bus, and per-component getters carry no
        // stopped gate — so the dispatcher drops the delivery once shutdown has gated the
        // container, or it would run handlers on destroyed singletons (#337). The drop is
        // logged, not silent, so a post-shutdown publish leaves a trace (#346).
        method.beginControlFlow("if (container.__isStopped())");
        method.addStatement("$T.logDroppedDuringShutdown(event)", CHAIN_CONTEXT);
        method.addStatement("return");
        method.endControlFlow();

        // Build the wrapper for this delivery and run the handler under it. Generated code
        // uses an explicit try/finally rather than EventChainContext.runWith so we don't
        // have to introduce a lambda — keeps the generated source readable.
        boolean lifecycleEvent = handler.getEventTypeName().startsWith("io.tiko.events.");
        boolean detachedUnit = handler.isAsync() && !lifecycleEvent;

        method.addStatement("$T<$T> __wrapper = $T.wrap(event)", Event.class, eventClass, CHAIN_CONTEXT);
        method.addStatement("$T<?> __previous = $T.enter(__wrapper)", Event.class, CHAIN_CONTEXT);
        method.beginControlFlow("try");
        if (!detachedUnit) {
            method.addStatement("$T __handler = container.$L()", declaringClass, getterName);
        }

        TypeMirror returnType = handler.getMethodElement().getReturnType();
        boolean hasTriggers = !handler.getEventTriggers().isEmpty();
        boolean returnsValue = returnType.getKind() != TypeKind.VOID;
        boolean captureResult = hasTriggers && returnsValue;

        if (hasTriggers && !returnsValue) {
            context.getMessager()
                    .printMessage(
                            Diagnostic.Kind.WARNING,
                            "@EventTrigger on a void-returning @EventHandler has no payload to publish — ignored",
                            handler.getMethodElement());
        }

        String invocation = handler.hasEventWrapper()
                ? "__handler." + handler.getMethodName() + "(event, __wrapper)"
                : "__handler." + handler.getMethodName() + "(event)";

        if (handler.isAsync()) {
            // Async dispatch: submit handler invocation to executor, route exceptional
            // completion to ErrorHandler via whenComplete.
            method.addStatement("$T __exec = container.getEventExecutor()", executorServiceClass);
            method.addStatement("$T __err = container.getErrorHandler()", errorHandler);
            method.addStatement("final $T<?> __asyncWrapper = __wrapper", Event.class);

            // Build the runAsync body
            CodeBlock.Builder runBody = CodeBlock.builder();
            if (detachedUnit) {
                runBody.addStatement("$T __handler = container.$L()", declaringClass, getterName);
            }
            runBody.addStatement("$T<?> __asyncPrev = $T.enter(__asyncWrapper)", Event.class, CHAIN_CONTEXT);
            runBody.beginControlFlow("try");
            if (captureResult) {
                runBody.addStatement("$T __result = $L", TypeName.get(returnType), invocation);
                for (EventTriggerModel trigger : handler.getEventTriggers()) {
                    emitTriggerInto(runBody, trigger, index);
                }
            } else {
                runBody.addStatement(invocation);
            }
            runBody.nextControlFlow("finally");
            runBody.addStatement("$T.exit(__asyncPrev)", CHAIN_CONTEXT);
            runBody.endControlFlow();

            CodeBlock asyncBody = detachedUnit
                    ? CodeBlock.builder()
                            .add("container.runInDetachedEventScope(() -> {\n$L});\n", runBody.build())
                            .build()
                    : runBody.build();

            if (handler.hasRetries()) {
                // Retry dispatch (#108): re-invoke on failure up to the budget, with backoff
                // scheduled between attempts and (when a timeout is also set) each attempt
                // time-boxed. The helper routes a single EventHandlerError carrying the attempt
                // count once the budget is exhausted. Composes the #107 timeout per attempt.
                method.addCode(CodeBlock.builder()
                        .add(
                                "$T.runAsyncWithRetry(() -> {\n$L}, new $T($L, $LL, $T.$L, $LL), __exec, __err, HANDLER_INFO_$L, event);\n",
                                CHAIN_CONTEXT,
                                asyncBody,
                                ClassName.get("io.tiko.runtime", "RetryPolicy"),
                                handler.getRetries(),
                                handler.getBackoffNanos(),
                                ClassName.get("io.tiko.annotations", "BackoffStrategy"),
                                handler.getBackoffStrategy().name(),
                                handler.getTimeoutNanos(),
                                index)
                        .build());
            } else if (handler.hasTimeout()) {
                // Timed dispatch (#107): run the invocation under a wall-clock budget. The runtime
                // helper submits the body to the executor as an interruptible Future, interrupts it
                // on breach (best-effort), frees the slot, and routes an EventHandlerError whose
                // cause is a TimeoutException. It applies the same Error-vs-Exception routing as the
                // plain async path below, so no whenComplete block is generated here.
                method.addCode(CodeBlock.builder()
                        .add(
                                "$T.runAsyncWithTimeout(() -> {\n$L}, $LL, __exec, __err, HANDLER_INFO_$L, event);\n",
                                CHAIN_CONTEXT,
                                asyncBody,
                                handler.getTimeoutNanos(),
                                index)
                        .build());
            } else {
                // Plain async dispatch (#111): route through runAsyncWithTimeout with a zero budget so
                // the base async path shares the executor-submit, ROUTE_TO_DLQ overflow handling, and
                // Error-vs-Exception routing of the timeout path. timeout = 0 means no time-boxing —
                // runOnce degrades to a bare runAsync — so behaviour matches the former inline
                // runAsync(...).whenComplete(...), minus the duplicated wiring (and now DLQ-covered: the
                // inline form bypassed EventChainContext and so leaked the overflow signal to the publisher).
                method.addCode(CodeBlock.builder()
                        .add(
                                "$T.runAsyncWithTimeout(() -> {\n$L}, 0L, __exec, __err, HANDLER_INFO_$L, event);\n",
                                CHAIN_CONTEXT,
                                asyncBody,
                                index)
                        .build());
            }

        } else {
            // Sync dispatch: inline try/catch, error routed immediately.
            method.beginControlFlow("try");
            if (captureResult) {
                method.addStatement("$T __result = $L", TypeName.get(returnType), invocation);
                for (EventTriggerModel trigger : handler.getEventTriggers()) {
                    emitTrigger(method, trigger, index);
                }
            } else {
                method.addStatement(invocation);
            }
            method.nextControlFlow("catch ($T __t)", Exception.class);
            method.addStatement("$T __err = container.getErrorHandler()", errorHandler);
            method.beginControlFlow("try");
            method.addStatement("__err.onError(new $T(HANDLER_INFO_$L, event, __t))", eventHandlerError, index);
            method.nextControlFlow("catch ($T __inner)", Exception.class);
            method.addStatement("$T.logErrorHandlerFailure(__inner)", CHAIN_CONTEXT);
            method.endControlFlow();
            method.endControlFlow();
        }

        method.nextControlFlow("finally");
        method.addStatement("$T.exit(__previous)", CHAIN_CONTEXT);
        method.endControlFlow();

        return method.build();
    }

    /**
     * Emits the publish call for one {@code @EventTrigger} into a {@link MethodSpec.Builder},
     * including any guard checks and the appropriate sync/async + spread/single variant.
     *
     * <p>Async triggers use the 6-arg {@code publishAsync}/{@code publishSpreadAsync} form
     * that passes the container's executor and error handler, plus the handler's
     * {@code HANDLER_INFO_<index>} constant so any exceptional completion can be attributed.
     */
    private void emitTrigger(MethodSpec.Builder method, EventTriggerModel trigger, int index) {
        String publishHelper;
        if (trigger.isAsync()) {
            publishHelper = trigger.isSpread() ? "publishSpreadAsync" : "publishAsync";
        } else {
            publishHelper = trigger.isSpread() ? "publishSpreadWithOrigin" : "publishWithOrigin";
        }

        String publishCall;
        if (trigger.isAsync()) {
            publishCall =
                    "$T.$L(eventBus, __result, __wrapper, container.getEventExecutor(), container.getErrorHandler(), HANDLER_INFO_"
                            + index + ")";
        } else {
            publishCall = "$T.$L(eventBus, __result, __wrapper)";
        }

        if (!trigger.hasGuard()) {
            method.addStatement(publishCall, CHAIN_CONTEXT, publishHelper);
            return;
        }

        // AND of guard.shouldTrigger(__result, event) for each declared guard.
        StringBuilder condition = new StringBuilder();
        Object[] args = new Object[trigger.getGuardClasses().size()];
        for (int i = 0; i < trigger.getGuardClasses().size(); i++) {
            if (i > 0) condition.append(" && ");
            condition.append("new $T().shouldTrigger(__result, event)");
            args[i] = ClassName.get(trigger.getGuardClasses().get(i));
        }

        method.beginControlFlow("if (" + condition + ")", args);
        method.addStatement(publishCall, CHAIN_CONTEXT, publishHelper);
        method.endControlFlow();
    }

    /**
     * Emits the publish call for one {@code @EventTrigger} into a {@link CodeBlock.Builder}
     * (used inside async lambda bodies where {@link MethodSpec.Builder} is not available).
     * Mirrors {@link #emitTrigger(MethodSpec.Builder, EventTriggerModel, int)} exactly, but
     * uses {@code __asyncWrapper} instead of {@code __wrapper} since the lambda captures the
     * wrapper under that name.
     *
     * <p>Async triggers use the 6-arg form; sync triggers use the 3-arg form.
     */
    private void emitTriggerInto(CodeBlock.Builder block, EventTriggerModel trigger, int index) {
        String publishHelper;
        if (trigger.isAsync()) {
            publishHelper = trigger.isSpread() ? "publishSpreadAsync" : "publishAsync";
        } else {
            publishHelper = trigger.isSpread() ? "publishSpreadWithOrigin" : "publishWithOrigin";
        }

        String publishCall;
        if (trigger.isAsync()) {
            publishCall =
                    "$T.$L(eventBus, __result, __asyncWrapper, container.getEventExecutor(), container.getErrorHandler(), HANDLER_INFO_"
                            + index + ")";
        } else {
            publishCall = "$T.$L(eventBus, __result, __asyncWrapper)";
        }

        if (!trigger.hasGuard()) {
            block.addStatement(publishCall, CHAIN_CONTEXT, publishHelper);
            return;
        }

        // AND of guard.shouldTrigger(__result, event) for each declared guard.
        StringBuilder condition = new StringBuilder();
        Object[] args = new Object[trigger.getGuardClasses().size()];
        for (int i = 0; i < trigger.getGuardClasses().size(); i++) {
            if (i > 0) condition.append(" && ");
            condition.append("new $T().shouldTrigger(__result, event)");
            args[i] = ClassName.get(trigger.getGuardClasses().get(i));
        }

        block.beginControlFlow("if (" + condition + ")", args);
        block.addStatement(publishCall, CHAIN_CONTEXT, publishHelper);
        block.endControlFlow();
    }

    private static String dispatcherName(EventHandlerModel handler, int index) {
        // Include both class and method names for readability when debugging generated code,
        // and an index so two methods with the same name on different classes don't collide.
        return "dispatch_" + handler.getDeclaringClass().getSimpleName() + "_" + handler.getMethodName() + "_" + index;
    }
}
