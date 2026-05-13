package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.util.GeneratorAnnotations;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Generates proxy classes for cross-scope injection.
 *
 * When a shorter-lived scoped bean (REQUEST, EVENT) is injected into a longer-lived scope,
 * we generate a proxy that implements the interface and delegates to the current scope instance.
 *
 * Example generated code:
 * <pre>
 * public final class RequestContextProxy implements RequestContext {
 *     private final TikoContainerImpl container;
 *
 *     public RequestContextProxy(TikoContainerImpl container) {
 *         this.container = container;
 *     }
 *
 *     @Override
 *     public String getRequestId() {
 *         return container.getCurrentRequestContext().getRequestId();
 *     }
 *
 *     @Override
 *     public String getUserId() {
 *         return container.getCurrentRequestContext().getUserId();
 *     }
 * }
 * </pre>
 */
public final class ProxyGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated";

    private final ProcessorContext context;

    public ProxyGenerator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Generates a proxy class for the given component if needed.
     * Returns true if proxy was generated, false if not needed.
     */
    public boolean generate(ComponentModel component) throws IOException {
        if (!component.requiresProxy()) {
            return false;
        }

        TypeMirror interfaceType = component
                .getImplementedInterface()
                .orElseThrow(() -> new IllegalStateException("Proxy required but no interface found"));

        TypeElement interfaceElement = context.getElementUtils().getTypeElement(interfaceType.toString());
        if (interfaceElement == null) {
            return false;
        }

        String proxyClassName = component.getClassName() + "Proxy";

        TypeSpec proxyClass = TypeSpec.classBuilder(proxyClassName)
                .addAnnotation(GeneratorAnnotations.generatedBy(ProxyGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(TypeName.get(interfaceType))
                .addField(createContainerField())
                .addMethod(createConstructor())
                .addMethods(createDelegatingMethods(component, interfaceElement))
                .build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, proxyClass).build();

        javaFile.writeTo(context.getFiler());
        return true;
    }

    /**
     * Creates the container field.
     */
    private FieldSpec createContainerField() {
        return FieldSpec.builder(
                        ClassName.get(GENERATED_PACKAGE, context.getContainerClassName()),
                        "container",
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                .build();
    }

    /**
     * Creates the constructor.
     */
    private MethodSpec createConstructor() {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get(GENERATED_PACKAGE, context.getContainerClassName()), "container")
                .addStatement("this.container = container")
                .build();
    }

    /**
     * Creates delegating methods for all interface methods.
     */
    private List<MethodSpec> createDelegatingMethods(ComponentModel component, TypeElement interfaceElement) {
        List<MethodSpec> methods = new ArrayList<>();

        for (Element enclosedElement : interfaceElement.getEnclosedElements()) {
            if (enclosedElement instanceof ExecutableElement method) {
                // Skip static and default methods
                if (method.getModifiers().contains(Modifier.STATIC)
                        || method.getModifiers().contains(Modifier.DEFAULT)) {
                    continue;
                }

                methods.add(createDelegatingMethod(component, method));
            }
        }

        return methods;
    }

    /**
     * Creates a single delegating method that forwards to the actual instance.
     */
    private MethodSpec createDelegatingMethod(ComponentModel component, ExecutableElement method) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(
                        method.getSimpleName().toString())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(TypeName.get(method.getReturnType()));

        // Add parameters
        List<String> paramNames = new ArrayList<>();
        method.getParameters().forEach(param -> {
            String paramName = param.getSimpleName().toString();
            paramNames.add(paramName);
            methodBuilder.addParameter(TypeName.get(param.asType()), paramName);
        });

        // Generate delegation call
        String getActualCall = generateGetActualInstanceCall(component);
        String params = String.join(", ", paramNames);

        if (method.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID) {
            methodBuilder.addStatement("$L.$L($L)", getActualCall, method.getSimpleName(), params);
        } else {
            methodBuilder.addStatement("return $L.$L($L)", getActualCall, method.getSimpleName(), params);
        }

        return methodBuilder.build();
    }

    /**
     * Generates the call to get the actual instance from the container.
     * e.g., "container.getCurrentRequestContext()"
     */
    private String generateGetActualInstanceCall(ComponentModel component) {
        String methodName =
                switch (component.getScope()) {
                    case REQUEST -> "getCurrent" + component.getClassName();
                    case EVENT -> "getCurrent" + component.getClassName();
                    default ->
                        throw new IllegalStateException(
                                "Proxy only for REQUEST/EVENT scope, got: " + component.getScope());
                };

        return "container." + methodName + "()";
    }
}
