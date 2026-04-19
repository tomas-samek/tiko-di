package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.EventBus;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.util.ProcessorContext;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;

/**
 * Generates the EventRegistry class that registers all @EventHandler methods.
 *
 * Example generated code:
 * <pre>
 * public final class EventRegistry {
 *     public static void registerHandlers(EventBus eventBus, TikoContainerImpl container) {
 *         // Register event handlers
 *         eventBus.subscribe(MessageCreatedEvent.class, event -> {
 *             AuditService handler = container.getAuditService();
 *             handler.onMessageCreated(event);
 *         });
 *
 *         eventBus.subscribe(UserRegisteredEvent.class, event -> {
 *             NotificationService handler = container.getNotificationService();
 *             handler.sendWelcomeEmail(event);
 *         });
 *     }
 * }
 * </pre>
 */
public final class EventRegistryGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated";
    private static final String REGISTRY_CLASS = "EventRegistry";

    private final ProcessorContext context;

    public EventRegistryGenerator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Generates the EventRegistry class.
     */
    public void generate() throws IOException {
        List<EventHandlerModel> eventHandlers = context.getEventHandlers();

        if (eventHandlers.isEmpty()) {
            // No event handlers - skip generation
            return;
        }

        TypeSpec registryClass = TypeSpec.classBuilder(REGISTRY_CLASS)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(createRegisterMethod(eventHandlers))
                .build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, registryClass)
                .build();

        javaFile.writeTo(context.getFiler());
    }

    /**
     * Creates the registerHandlers method.
     */
    private MethodSpec createRegisterMethod(List<EventHandlerModel> eventHandlers) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("registerHandlers")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get(EventBus.class), "eventBus")
                .addParameter(ClassName.get(GENERATED_PACKAGE, context.getContainerClassName()), "container");

        // Add comment
        methodBuilder.addComment("Register all event handlers");

        // Register each event handler
        for (EventHandlerModel handler : eventHandlers) {
            methodBuilder.addCode("\n");
            addEventHandlerRegistration(methodBuilder, handler);
        }

        return methodBuilder.build();
    }

    /**
     * Adds registration code for a single event handler.
     */
    private void addEventHandlerRegistration(MethodSpec.Builder methodBuilder, EventHandlerModel handler) {
        String eventTypeName = handler.getEventTypeName();
        String declaringClassName = handler.getDeclaringClass().getSimpleName().toString();
        String methodName = handler.getMethodName();

        // Generate getter method name for the component
        String getterName = "get" + declaringClassName;

        // Create lambda for event handler
        if (handler.hasEventWrapper()) {
            // Handler takes both event and Event<?> wrapper
            methodBuilder.addStatement(
                    "eventBus.subscribe($T.class, (event, wrapper) -> {\n" +
                    "    $T handler = container.$L();\n" +
                    "    handler.$L(event, wrapper);\n" +
                    "})",
                    ClassName.bestGuess(eventTypeName),
                    ClassName.bestGuess(handler.getDeclaringClass().getQualifiedName().toString()),
                    getterName,
                    methodName
            );
        } else {
            // Handler takes only the event
            methodBuilder.addStatement(
                    "eventBus.subscribe($T.class, event -> {\n" +
                    "    $T handler = container.$L();\n" +
                    "    handler.$L(event);\n" +
                    "})",
                    ClassName.bestGuess(eventTypeName),
                    ClassName.bestGuess(handler.getDeclaringClass().getQualifiedName().toString()),
                    getterName,
                    methodName
            );
        }
    }
}
