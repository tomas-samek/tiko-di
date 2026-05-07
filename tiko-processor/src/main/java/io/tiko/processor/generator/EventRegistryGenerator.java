package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.Event;
import io.tiko.EventBus;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.model.EventTriggerModel;
import io.tiko.processor.util.ProcessorContext;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.List;

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
    private static final String REGISTRY_CLASS = "EventRegistry";

    private static final ClassName CHAIN_CONTEXT = ClassName.get("io.tiko.runtime", "EventChainContext");

    private final ProcessorContext context;

    public EventRegistryGenerator(ProcessorContext context) {
        this.context = context;
    }

    public void generate() throws IOException {
        List<EventHandlerModel> eventHandlers = context.getEventHandlers();

        if (eventHandlers.isEmpty()) {
            return;
        }

        TypeSpec.Builder registry = TypeSpec.classBuilder(REGISTRY_CLASS)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        registry.addMethod(createRegisterMethod(eventHandlers));

        ClassName eventHandlerInfo = ClassName.get("io.tiko", "EventHandlerInfo");

        // Emit one HANDLER_INFO_<n> static constant per handler
        for (int i = 0; i < eventHandlers.size(); i++) {
            EventHandlerModel handler = eventHandlers.get(i);
            ClassName declaring = ClassName.bestGuess(handler.getDeclaringClass().getQualifiedName().toString());
            ClassName eventClass = ClassName.bestGuess(handler.getEventTypeName());

            FieldSpec info = FieldSpec.builder(eventHandlerInfo, "HANDLER_INFO_" + i,
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($T.class, $S, $T.class, $L)", eventHandlerInfo, declaring,
                            handler.getMethodName(), eventClass, false)
                    .build();
            registry.addField(info);
        }

        // Emit one private helper per handler
        for (int i = 0; i < eventHandlers.size(); i++) {
            registry.addMethod(createDispatcherMethod(eventHandlers.get(i), i));
        }

        JavaFile.builder(GENERATED_PACKAGE, registry.build())
                .build()
                .writeTo(context.getFiler());
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

    /**
     * The per-handler helper. Centralises chain-context bookkeeping and trigger logic so
     * the lambdas in {@code registerHandlers} stay one-liners.
     */
    private MethodSpec createDispatcherMethod(EventHandlerModel handler, int index) {
        ClassName containerClass = ClassName.get(GENERATED_PACKAGE, context.getContainerClassName());
        ClassName eventClass = ClassName.bestGuess(handler.getEventTypeName());
        ClassName declaringClass = ClassName.bestGuess(handler.getDeclaringClass().getQualifiedName().toString());
        String getterName = "get" + handler.getDeclaringClass().getSimpleName().toString();

        MethodSpec.Builder method = MethodSpec.methodBuilder(dispatcherName(handler, index))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(ClassName.get(EventBus.class), "eventBus")
                .addParameter(containerClass, "container")
                .addParameter(eventClass, "event");

        // Build the wrapper for this delivery and run the handler under it. Generated code
        // uses an explicit try/finally rather than EventChainContext.runWith so we don't
        // have to introduce a lambda — keeps the generated source readable.
        method.addStatement("$T<$T> __wrapper = $T.wrap(event)", Event.class, eventClass, CHAIN_CONTEXT);
        method.addStatement("$T<?> __previous = $T.enter(__wrapper)", Event.class, CHAIN_CONTEXT);
        method.beginControlFlow("try");
        method.addStatement("$T __handler = container.$L()", declaringClass, getterName);

        TypeMirror returnType = handler.getMethodElement().getReturnType();
        boolean hasTriggers = !handler.getEventTriggers().isEmpty();
        boolean returnsValue = returnType.getKind() != TypeKind.VOID;
        boolean captureResult = hasTriggers && returnsValue;

        String invocation = handler.hasEventWrapper()
                ? "__handler." + handler.getMethodName() + "(event, __wrapper)"
                : "__handler." + handler.getMethodName() + "(event)";

        if (hasTriggers && !returnsValue) {
            context.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "@EventTrigger on a void-returning @EventHandler has no payload to publish — ignored",
                    handler.getMethodElement());
        }

        ClassName errorHandler = ClassName.get("io.tiko", "ErrorHandler");
        ClassName eventHandlerError = ClassName.get("io.tiko", "EventHandlerError");

        method.beginControlFlow("try");
        if (captureResult) {
            method.addStatement("$T __result = $L", TypeName.get(returnType), invocation);
        } else {
            method.addStatement(invocation);
        }

        if (captureResult) {
            for (EventTriggerModel trigger : handler.getEventTriggers()) {
                emitTrigger(method, trigger);
            }
        }

        method.nextControlFlow("catch ($T __t)", Exception.class);
        method.addStatement("$T __err = container.getErrorHandler()", errorHandler);
        method.beginControlFlow("try");
        method.addStatement("__err.onError(new $T(HANDLER_INFO_$L, event, __t))", eventHandlerError, index);
        method.nextControlFlow("catch ($T __inner)", Exception.class);
        method.addStatement("$T.logErrorHandlerFailure(__inner)", CHAIN_CONTEXT);
        method.endControlFlow();
        method.endControlFlow();

        method.nextControlFlow("finally");
        method.addStatement("$T.exit(__previous)", CHAIN_CONTEXT);
        method.endControlFlow();

        return method.build();
    }

    /**
     * Emits the publish call for one {@code @EventTrigger}, including any guard checks and
     * the appropriate sync/async + spread/single variant.
     */
    private void emitTrigger(MethodSpec.Builder method, EventTriggerModel trigger) {
        String publishHelper;
        if (trigger.isAsync()) {
            publishHelper = trigger.isSpread() ? "publishSpreadAsync" : "publishAsync";
        } else {
            publishHelper = trigger.isSpread() ? "publishSpreadWithOrigin" : "publishWithOrigin";
        }

        if (!trigger.hasGuard()) {
            method.addStatement("$T.$L(eventBus, __result, __wrapper)", CHAIN_CONTEXT, publishHelper);
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
        method.addStatement("$T.$L(eventBus, __result, __wrapper)", CHAIN_CONTEXT, publishHelper);
        method.endControlFlow();
    }

    private static String dispatcherName(EventHandlerModel handler, int index) {
        // Include both class and method names for readability when debugging generated code,
        // and an index so two methods with the same name on different classes don't collide.
        return "dispatch_"
                + handler.getDeclaringClass().getSimpleName().toString()
                + "_" + handler.getMethodName()
                + "_" + index;
    }
}
